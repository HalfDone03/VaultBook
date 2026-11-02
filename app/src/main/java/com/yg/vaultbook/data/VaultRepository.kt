package com.yg.vaultbook.data

import androidx.room.withTransaction
import com.yg.vaultbook.core.crypto.FieldCrypto
import com.yg.vaultbook.core.matcher.MatchCandidate
import com.yg.vaultbook.core.matcher.RankInput
import com.yg.vaultbook.core.matcher.SiteMatcher
import com.yg.vaultbook.core.util.Ids
import com.yg.vaultbook.core.util.Normalize
import com.yg.vaultbook.core.util.PasswordStrength
import com.yg.vaultbook.core.util.Reorder
import com.yg.vaultbook.data.backup.CsvRow
import com.yg.vaultbook.data.backup.ImportResult
import com.yg.vaultbook.data.backup.SnapshotCategory
import com.yg.vaultbook.data.backup.SnapshotEntry
import com.yg.vaultbook.data.backup.SnapshotField
import com.yg.vaultbook.data.backup.VaultSnapshot
import com.yg.vaultbook.data.entity.AuditLogEntity
import com.yg.vaultbook.data.entity.CategoryEntity
import com.yg.vaultbook.data.entity.CustomFieldEntity
import com.yg.vaultbook.data.entity.EntryEntity
import com.yg.vaultbook.data.entity.EntryHistoryEntity
import com.yg.vaultbook.data.entity.EntrySummaryRow
import com.yg.vaultbook.domain.CustomFieldDraft
import com.yg.vaultbook.domain.EntryDetail
import com.yg.vaultbook.domain.EntryDraft
import com.yg.vaultbook.domain.EntrySummary
import com.yg.vaultbook.domain.PlainCategory
import com.yg.vaultbook.domain.PlainCustomField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 保险库仓储。**唯一**知道"哪个字段是密文、用什么 AAD 解密"的地方。
 *
 * 分层理由：DAO 只认 `ByteArray`，不认识业务含义；UI 只认 `String`/`CharArray`。
 * 加解密集中在这一层，就不会出现"某处忘了加密"或"某处用错了 AAD"。
 *
 * ⚠️ 本类的实例**只在解锁期间存在**。锁定后 `VaultGateway` 会丢弃它，
 * 因此不需要在这里再判 `session.isUnlocked`。但 Flow 可能还在被收集 ——
 * `toSummary` 对解密失败做了兜底，避免锁定瞬间列表崩成红屏。
 */
class VaultRepository(
    private val db: VaultDatabase,
    private val cipher: FieldCrypto,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    // ── 读 ────────────────────────────────────────────────────────

    /**
     * 列表页数据源。走投影查询（不读备注/密码密文），解密在 [Dispatchers.Default] 上做。
     */
    fun observeSummaries(): Flow<List<EntrySummary>> =
        db.entryDao().observeSummaryRows()
            .map { rows -> rows.map { toSummary(it) } }
            .flowOn(Dispatchers.Default)

    suspend fun loadSummaries(): List<EntrySummary> =
        db.entryDao().loadSummaryRows().map { toSummary(it) }

    suspend fun loadTrashed(): List<EntrySummary> =
        db.entryDao().loadTrashed().map { toSummary(it) }

    /**
     * 备注明文，供搜索索引用（`id` → 备注）。
     *
     * 只返回有备注的条目：空备注不必占一个键，调用方也少判一次。
     * 解密失败的那条直接跳过 —— 一条备注坏了不该让整次搜索索引构建失败，
     * 它仍能用标题/账号搜到。
     *
     * ⚠️ 返回值里是明文，调用方用完即弃，不要塞进任何 UI 状态或日志。
     */
    suspend fun loadNoteTexts(): Map<String, String> {
        val out = HashMap<String, String>()
        for (row in db.entryDao().loadNotesCipher()) {
            val text = runCatching {
                cipher.decryptString(row.id, FIELD_NOTES, row.notesCt)
            }.getOrNull()
            if (!text.isNullOrBlank()) out[row.id] = text
        }
        return out
    }

    /**
     * 自动填充的候选条目，已按匹配度排序。
     *
     * 只查"这个站点/这个包名"相关的行，**不做全表扫描** ——
     * 全表意味着每次输入框获得焦点都要解密全部条目，几百条时会有肉眼可见的卡顿。
     *
     * @param username 页面上已经填好的账号。用来把匹配该账号的条目顶到前面
     *                 （同一站点常有多个账号，填错比不填更麻烦）。
     */
    suspend fun candidatesFor(
        host: String?,
        packageName: String?,
        username: String?
    ): List<MatchCandidate> {
        if (host == null && packageName == null) return emptyList()

        val dao = db.entryDao()
        val rows = LinkedHashMap<String, EntryEntity>()
        // 父域也要查：条目存的是 taobao.com，而这里拿到的可能是 new.taobao.com
        if (host != null) {
            for (key in hostCandidates(host)) {
                dao.findActiveByHost(key).forEach { rows.putIfAbsent(it.id, it) }
            }
        }
        if (packageName != null) {
            dao.findActiveByPackage(packageName).forEach { rows.putIfAbsent(it.id, it) }
        }
        if (rows.isEmpty()) return emptyList()

        val mapped = buildSet {
            host?.let { h -> db.siteMappingDao().byHost(h).forEach { if (it.hostNorm != null) add(it.entryId) } }
            packageName?.let { p -> db.siteMappingDao().byPackage(p).forEach { add(it.entryId) } }
        }

        val inputs = rows.values.map { e ->
            val hostNorm = e.hostNorm
            RankInput(
                entryId = e.id,
                mapped = e.id in mapped,
                hostExact = host != null && hostNorm == host,
                packageExact = packageName != null && e.packageNorm == packageName,
                hostSuffix = host != null && hostNorm != null &&
                    host.length > hostNorm.length && host.endsWith(".$hostNorm"),
                usernameMatches = username != null && matchesUsername(e, username),
                favorite = e.favorite,
                useCount = e.useCount,
                lastUsedAt = e.lastUsedAt,
                hasPassword = e.passwordCt != null
            )
        }
        return SiteMatcher.rank(inputs, clock())
    }

    /**
     * 只取一条的摘要（标题/账号/网址），不碰备注与自定义字段。
     *
     * 自动填充列出候选时用这个：候选最多 5 条，走 [loadDetail] 会顺带解密
     * 每条的备注和全部自定义字段，纯属浪费。
     */
    suspend fun summaryOf(id: String): EntrySummary? =
        db.entryDao().findById(id)?.let { toSummary(it) }

    suspend fun loadDetail(id: String): EntryDetail? {
        val e = db.entryDao().findById(id) ?: return null
        val fields = db.customFieldDao().forEntry(id).map { f ->
            PlainCustomField(
                id = f.id,
                label = cipher.decryptString(f.id, FIELD_CF_LABEL, f.labelCt) ?: "",
                value = cipher.decryptString(f.id, FIELD_CF_VALUE, f.valueCt) ?: "",
                type = f.type,
                sort = f.sort
            )
        }
        return EntryDetail(
            summary = toSummary(e),
            notes = cipher.decryptString(id, FIELD_NOTES, e.notesCt),
            totp = cipher.decryptString(id, FIELD_TOTP, e.totpCt),
            customFields = fields
        )
    }

    /**
     * 解密并返回密码。**调用方负责 `fill('\u0000')`**。
     * 这里不缓存：缓存就等于把密码常驻内存，与设计目标相反。
     */
    suspend fun revealPassword(id: String): CharArray? {
        val e = db.entryDao().findById(id) ?: return null
        return cipher.decrypt(id, FIELD_PASSWORD, e.passwordCt)
    }

    /** 密码历史（用于"不小心改错了"回滚）。返回最新的在前。 */
    suspend fun passwordHistory(id: String): List<CharArray> =
        db.entryHistoryDao().all(id).mapNotNull {
            cipher.decrypt(id, FIELD_PASSWORD, it.passwordCt)
        }

    suspend fun categories(): List<PlainCategory> =
        db.categoryDao().loadAll().map { toPlainCategory(it) }

    /**
     * 分类列表的响应式版本，列表页筛选条用。
     *
     * 为什么不能靠"条目数变了就重取一次"：改分类名不改条目数，
     * 那种写法下改完名回到列表，筛选条上还是旧名字，要杀进程才更新。
     * 直接订阅 Room 的 Flow，改一次刷一次。
     */
    fun observeCategories(): Flow<List<PlainCategory>> =
        db.categoryDao().observeAll()
            .map { rows -> rows.map { toPlainCategory(it) } }
            .flowOn(Dispatchers.Default)

    private fun toPlainCategory(c: CategoryEntity): PlainCategory =
        PlainCategory(
            id = c.id,
            name = cipher.decryptString(c.id, FIELD_CATEGORY_NAME, c.nameCt) ?: "",
            icon = c.icon,
            color = c.color,
            sort = c.sort,
            builtin = c.builtin
        )

    // ── 写 ────────────────────────────────────────────────────────

    /**
     * 新建条目。
     * @return 新条目 id
     */
    suspend fun createEntry(draft: EntryDraft): String {
        val id = Ids.newId()
        val now = clock()
        val title = draft.title.trim().ifBlank { "未命名" }
        val pwd = draft.password
        val hasPwd = pwd != null && pwd.isNotEmpty()

        val entity = EntryEntity(
            id = id,
            categoryId = draft.categoryId,
            titleCt = requireNotNull(cipher.encryptString(id, FIELD_TITLE, title)),
            usernameCt = cipher.encryptString(id, FIELD_USERNAME, draft.username?.trim()?.takeIf { it.isNotEmpty() }),
            passwordCt = if (hasPwd) cipher.encrypt(id, FIELD_PASSWORD, pwd) else null,
            urlCt = cipher.encryptString(id, FIELD_URL, draft.url?.trim()?.takeIf { it.isNotEmpty() }),
            notesCt = cipher.encryptString(id, FIELD_NOTES, draft.notes?.takeIf { it.isNotBlank() }),
            totpCt = cipher.encryptString(id, FIELD_TOTP, draft.totp?.trim()?.takeIf { it.isNotEmpty() }),
            hostNorm = Normalize.hostFromUrl(draft.url),
            packageNorm = null,
            favorite = draft.favorite,
            strengthScore = if (hasPwd) PasswordStrength.score(pwd!!) else 0,
            pwdUpdatedAt = if (hasPwd) now else null,
            createdAt = now,
            updatedAt = now,
            // 新建即"刚用过"：列表按 last_used_at 排序，如果这里留 null，
            // 刚建好的条目会排到所有用过的记录后面 —— 用户新建完回到列表，
            // 发现它不在第一屏，会以为没保存成功。
            lastUsedAt = now,
            useCount = 1
        )

        db.withTransaction {
            db.entryDao().upsert(entity)
            writeCustomFields(id, draft.customFields)
            db.auditLogDao().insert(
                AuditLogEntity(
                    event = AuditLogEntity.EVENT_CREATE,
                    targetId = id,
                    detail = null,
                    createdAt = now
                )
            )
        }
        db.auditLogDao().trim(AUDIT_KEEP)
        return id
    }

    /**
     * 更新条目。
     *
     * 密码处理是这里唯一复杂的地方：
     * - `draft.password == null` → 保留原密码密文（即使老密码是空的也保持空）
     * - `draft.password` 非空 → 旧密码密文先推进历史，再写新密文
     *
     * 推进历史必须发生在新密文写入**之前**，否则旧密码就永久丢了。
     */
    suspend fun updateEntry(id: String, draft: EntryDraft) {
        val existing = db.entryDao().findById(id)
            ?: throw IllegalArgumentException("条目不存在：$id")
        val now = clock()
        val title = draft.title.trim().ifBlank { "未命名" }
        val newPwd = draft.password
        val changingPwd = newPwd != null && newPwd.isNotEmpty()

        val pwdCt: ByteArray?
        val pwdUpdatedAt: Long?
        val score: Int

        if (changingPwd) {
            pwdCt = cipher.encrypt(id, FIELD_PASSWORD, newPwd)
            pwdUpdatedAt = now
            score = PasswordStrength.score(newPwd!!)
        } else {
            pwdCt = existing.passwordCt
            pwdUpdatedAt = existing.pwdUpdatedAt
            score = existing.strengthScore
        }

        val updated = existing.copy(
            categoryId = draft.categoryId,
            titleCt = requireNotNull(cipher.encryptString(id, FIELD_TITLE, title)),
            usernameCt = cipher.encryptString(id, FIELD_USERNAME, draft.username?.trim()?.takeIf { it.isNotEmpty() }),
            passwordCt = pwdCt,
            urlCt = cipher.encryptString(id, FIELD_URL, draft.url?.trim()?.takeIf { it.isNotEmpty() }),
            notesCt = cipher.encryptString(id, FIELD_NOTES, draft.notes?.takeIf { it.isNotBlank() }),
            totpCt = cipher.encryptString(id, FIELD_TOTP, draft.totp?.trim()?.takeIf { it.isNotEmpty() }),
            hostNorm = Normalize.hostFromUrl(draft.url),
            favorite = draft.favorite,
            strengthScore = score,
            pwdUpdatedAt = pwdUpdatedAt,
            updatedAt = now,
            // 编辑也是"在用"：改完回到列表，用户期待它就在眼前，
            // 而不是因为从没复制过就沉到列表底部。
            lastUsedAt = now
        )

        db.withTransaction {
            if (changingPwd) {
                existing.passwordCt?.let { old ->
                    db.entryHistoryDao().insert(
                        EntryHistoryEntity(
                            id = Ids.newId(),
                            entryId = id,
                            passwordCt = old,
                            changedAt = now
                        )
                    )
                    db.entryHistoryDao().trim(id, HISTORY_KEEP)
                }
            }
            db.entryDao().upsert(updated)
            db.customFieldDao().deleteForEntry(id)
            writeCustomFields(id, draft.customFields)
            db.auditLogDao().insert(
                AuditLogEntity(
                    event = AuditLogEntity.EVENT_UPDATE,
                    targetId = id,
                    detail = if (changingPwd) "password changed" else null,
                    createdAt = now
                )
            )
        }
        db.auditLogDao().trim(AUDIT_KEEP)
    }

    /**
     * 只替换密码，其余字段原样保留。
     *
     * 自动填充通道捕获到"用户在某个已知条目上改了密码"时走这条路：
     * 它手上只有账号和密码，没有标题、备注、自定义字段 ——
     * 若改用 [updateEntry] 就必须先把整条记录读出来再原样写回，
     * 那样会把没被填过的字段也用新 IV 重加密一遍，平白增加写放大与出错面。
     *
     * 旧密码同样先进历史再覆盖（顺序与 [updateEntry] 一致）。
     *
     * @return false 表示条目不存在或新密码为空
     */
    suspend fun replacePassword(id: String, newPassword: CharArray): Boolean {
        if (newPassword.isEmpty()) return false
        val existing = db.entryDao().findById(id) ?: return false

        val now = clock()
        val newCt = cipher.encrypt(id, FIELD_PASSWORD, newPassword)
        val score = PasswordStrength.score(newPassword)

        db.withTransaction {
            existing.passwordCt?.let { old ->
                db.entryHistoryDao().insert(
                    EntryHistoryEntity(
                        id = Ids.newId(),
                        entryId = id,
                        passwordCt = old,
                        changedAt = now
                    )
                )
                db.entryHistoryDao().trim(id, HISTORY_KEEP)
            }
            db.entryDao().upsert(
                existing.copy(
                    passwordCt = newCt,
                    pwdUpdatedAt = now,
                    strengthScore = score,
                    updatedAt = now
                )
            )
            db.auditLogDao().insert(
                AuditLogEntity(
                    event = AuditLogEntity.EVENT_UPDATE,
                    targetId = id,
                    detail = "password changed",
                    createdAt = now
                )
            )
        }
        db.auditLogDao().trim(AUDIT_KEEP)
        return true
    }

    /** 软删除，进回收站。 */
    suspend fun trashEntry(id: String) {
        val now = clock()
        db.entryDao().softDelete(id, now)
        audit(AuditLogEntity.EVENT_DELETE, id, "trashed")
    }

    suspend fun restoreEntry(id: String) {
        db.entryDao().restore(id, clock())
    }

    /**
     * 把回收站里的条目全部恢复。
     *
     * 逐条 [restoreEntry] 而不是一条 `UPDATE ... WHERE deleted_at IS NOT NULL`：
     * 这样才能给每条留一条审计（回收站里通常是误删的一批，恢复之后
     * "到底恢复了哪些"仍可回溯），代价是 N 次 update，N 是回收站条数，不会大。
     *
     * @return 实际恢复的条数
     */
    suspend fun restoreAllTrashed(): Int {
        val trashed = db.entryDao().loadTrashed()
        for (row in trashed) {
            db.entryDao().restore(row.id, clock())
        }
        if (trashed.isNotEmpty()) {
            audit(AuditLogEntity.EVENT_DELETE, null, "restored ${trashed.size} entries")
        }
        return trashed.size
    }

    /** 物理删除，不可恢复。 */
    suspend fun purgeEntry(id: String) {
        db.entryDao().purge(id)
        audit(AuditLogEntity.EVENT_DELETE, id, "purged")
    }

    suspend fun markUsed(id: String) {
        db.entryDao().markUsed(id, clock())
    }

    /** 回收站到期清理。解锁后调用一次即可。 */
    suspend fun purgeExpiredTrash(): Int {
        val before = clock() - TRASH_RETENTION_MS
        return db.entryDao().purgeTrashedBefore(before)
    }

    /**
     * 清空回收站（用户主动）。
     *
     * 与 [purgeExpiredTrash] 的区别：不看时间，把所有软删除的条目全物理删除。
     * 回收站页面的"清空"按钮走这条。
     *
     * 逐条 purge 而不是一条 SQL，是为了让 `entry_history` / `custom_fields` /
     * `site_mappings` 的 `ON DELETE CASCADE` 按 SQLite 的正常路径生效，
     * 同时每条都能留下一条审计（只记 id，不记内容）。
     */
    suspend fun purgeAllTrashed(): Int {
        val trashed = db.entryDao().loadTrashed()
        for (row in trashed) {
            db.entryDao().purge(row.id)
        }
        if (trashed.isNotEmpty()) {
            audit(AuditLogEntity.EVENT_DELETE, null, "purged ${trashed.size} entries")
        }
        return trashed.size
    }

    suspend fun audit(event: String, targetId: String? = null, detail: String? = null) {
        db.auditLogDao().insert(
            AuditLogEntity(event = event, targetId = targetId, detail = detail, createdAt = clock())
        )
        db.auditLogDao().trim(AUDIT_KEEP)
    }

    suspend fun recentAudit(limit: Int = 200): List<AuditLogEntity> =
        db.auditLogDao().recent(limit)

    // ── 初始化 ────────────────────────────────────────────────────

    /**
     * 补种内置分类。**增量式**：已存在的 id 不动，只补缺的。
     *
     * 不是"首次建库才写"（早期版本是那样写的），因为已经存过数据的老库
     * 永远拿不到后加的分类 —— 用户升级完发现分类没变多，会以为功能没做。
     * 现在的做法是每次打开都过一遍：缺哪个补哪个，已有的一个字节都不碰
     * （用户可能改过名、调过顺序，不能覆盖）。
     *
     * 分类 id 用固定字符串（`builtin_login` 等）而不是随机 UUID，
     * 因为它是 `name_ct` 密文 AAD 的一部分，必须跨设备/跨重装稳定 ——
     * 否则从备份导入分类名时会全部解不开。
     */
    suspend fun seedBuiltinCategoriesIfNeeded() {
        val existing = db.categoryDao().loadAll().mapTo(HashSet()) { it.id }
        var nextSort = (db.categoryDao().maxSort() ?: -1) + 1
        val rows = BUILTIN_CATEGORIES
            .filter { (id, _, _) -> id !in existing }
            .map { (id, name, icon) ->
                CategoryEntity(
                    id = id,
                    nameCt = requireNotNull(cipher.encryptString(id, FIELD_CATEGORY_NAME, name)),
                    icon = icon,
                    sort = nextSort++,
                    builtin = true
                )
            }
        if (rows.isEmpty()) return
        db.categoryDao().upsertAll(rows)
    }

    // ── 分类维护 ──────────────────────────────────────────────────

    /**
     * 新建分类。名称先 trim，空名直接拒绝（返回 null）。
     *
     * 排到当前最大 sort 之后，而不是插到最前 —— 用户新建一个分类时
     * 期待它出现在列表末尾，符合"添加"的空间直觉。
     */
    suspend fun createCategory(
        name: String,
        icon: String? = null,
        color: String? = null
    ): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val id = Ids.newId()
        val sort = (db.categoryDao().maxSort() ?: -1) + 1
        db.categoryDao().upsertAll(
            listOf(
                CategoryEntity(
                    id = id,
                    nameCt = requireNotNull(cipher.encryptString(id, FIELD_CATEGORY_NAME, trimmed)),
                    icon = icon,
                    color = color,
                    sort = sort,
                    builtin = false
                )
            )
        )
        audit(AuditLogEntity.EVENT_CREATE, id, "category")
        return id
    }

    /**
     * 改名。内置分类也允许改名（"银行卡"改成"卡包"是合理需求），
     * 只有**删除**才拒绝内置分类。
     *
     * 复用了原 id，所以 `name_ct` 的 AAD 不变，旧密文直接替换即可。
     */
    suspend fun renameCategory(id: String, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val existing = db.categoryDao().loadAll().firstOrNull { it.id == id } ?: return false
        db.categoryDao().upsertAll(
            listOf(
                existing.copy(
                    nameCt = requireNotNull(cipher.encryptString(id, FIELD_CATEGORY_NAME, trimmed))
                )
            )
        )
        audit(AuditLogEntity.EVENT_UPDATE, id, "category renamed")
        return true
    }

    /**
     * 删除分类。内置分类不可删（返回 false）。
     *
     * 先把这个分类下的条目 `category_id` 置空，再删分类 —— 条目本身不动，
     * 它们只是变成"未分类"。绝不级联删条目。
     */
    suspend fun deleteCategory(id: String): Boolean {
        val existing = db.categoryDao().loadAll().firstOrNull { it.id == id } ?: return false
        if (existing.builtin) return false
        db.withTransaction {
            db.entryDao().clearCategory(id)
            db.categoryDao().delete(id)
        }
        audit(AuditLogEntity.EVENT_DELETE, id, "category")
        return true
    }

    /** 上移/下移。交换相邻两项后把 sort 重写成 0..n-1，避免出现重复值。 */
    suspend fun moveCategory(id: String, delta: Int): Boolean {
        val rows = db.categoryDao().loadAll()
        val index = rows.indexOfFirst { it.id == id }
        if (index < 0) return false
        // 边界交给 Reorder 判：越界返回 null 就表示"已经在头/尾"
        val swapped = Reorder.swap(rows, index, index + delta) ?: return false
        db.categoryDao().upsertAll(swapped.mapIndexed { i, c -> c.copy(sort = i) })
        return true
    }

    /** 分类下还有多少条（含回收站）。删除前给用户看一眼。 */
    suspend fun countEntriesInCategory(id: String): Int = db.entryDao().countByCategory(id)

    // ── 审计日志 ──────────────────────────────────────────────────

    /** 审计日志条数，用于设置页显示"已记录 N 条" */
    suspend fun auditCount(): Int = db.auditLogDao().count()

    /**
     * 清空审计日志。
     *
     * 只清历史，**不**顺带记一条 "audit cleared" —— 那样清空之后日志里还有一条，
     * 用户会以为"没清干净"。清空本身就是要抹掉痕迹，包括这一动作。
     */
    suspend fun clearAudit() {
        db.auditLogDao().clear()
    }

    // ── 备份：导出 / 导入 ────────────────────────────────────────────

    /**
     * 把整个库（含回收站）读成一份明文快照。
     *
     * **这是全工程唯一一次把所有密码同时解密到内存的地方**。
     * 调用方必须在用完之后立刻 `snapshot.clearSecrets()`，
     * 并且不要把这个对象存进任何长期持有的字段里。
     *
     * 走 `Dispatchers.Default`：几百条要解密上千个字段，纯 CPU 活。
     */
    suspend fun exportSnapshot(): VaultSnapshot = withContext(Dispatchers.Default) {
        val categories = db.categoryDao().loadAll().map { c ->
            SnapshotCategory(
                id = c.id,
                name = cipher.decryptString(c.id, FIELD_CATEGORY_NAME, c.nameCt) ?: "(无法解密)",
                icon = c.icon,
                color = c.color,
                sort = c.sort,
                builtin = c.builtin
            )
        }

        // 一次取全部自定义字段再分组，避免按条目逐条查（N+1）
        val fieldsByEntry = db.customFieldDao().loadAll().groupBy { it.entryId }

        val entries = db.entryDao().loadEverything().map { e ->
            val fields = (fieldsByEntry[e.id] ?: emptyList()).map { f ->
                SnapshotField(
                    id = f.id,
                    label = cipher.decryptString(f.id, FIELD_CF_LABEL, f.labelCt).orEmpty(),
                    value = cipher.decryptString(f.id, FIELD_CF_VALUE, f.valueCt).orEmpty(),
                    type = f.type,
                    sort = f.sort
                )
            }
            SnapshotEntry(
                id = e.id,
                categoryId = e.categoryId,
                title = cipher.decryptString(e.id, FIELD_TITLE, e.titleCt) ?: "(无法解密)",
                username = cipher.decryptString(e.id, FIELD_USERNAME, e.usernameCt),
                password = cipher.decrypt(e.id, FIELD_PASSWORD, e.passwordCt),
                url = cipher.decryptString(e.id, FIELD_URL, e.urlCt),
                notes = cipher.decryptString(e.id, FIELD_NOTES, e.notesCt),
                totp = cipher.decryptString(e.id, FIELD_TOTP, e.totpCt),
                favorite = e.favorite,
                createdAt = e.createdAt,
                updatedAt = e.updatedAt,
                pwdUpdatedAt = e.pwdUpdatedAt,
                customFields = fields,
                hostNorm = e.hostNorm,
                packageNorm = e.packageNorm,
                lastUsedAt = e.lastUsedAt,
                useCount = e.useCount,
                deletedAt = e.deletedAt
            )
        }

        VaultSnapshot(createdAt = clock(), categories = categories, entries = entries)
    }

    /**
     * 把一份快照写回库。
     *
     * 冲突策略由 [overwrite] 决定：
     * - `false`（默认）：id 已存在就跳过。适合"把旧手机的备份合并进来"——
     *   本地那条可能已经改得更新，不该被几周前的文件盖掉。
     * - `true`：直接覆盖。适合"我刚把库搞乱了，用今天的备份还原"。
     *
     * ── 三个刻意不做的事 ────────────────────────────────────────
     * 1. **不写 entry_history**：导入不是"用户改密码"，把备份里的密码塞进历史
     *    只会在详情页多出一堆用户没做过的记录。
     * 2. **不动 site_mappings**：站点映射是"本机用出来的"，
     *    导入别人的备份不该改写本机的自动填充偏好。
     * 3. **分类只增不改**：同名分类可能被用户本地改过名，覆盖它等于抹掉用户的整理。
     */
    suspend fun importSnapshot(snapshot: VaultSnapshot, overwrite: Boolean): ImportResult {
        val now = clock()
        return db.withTransaction {
            // ── 1. 分类先落，否则条目的外键找不到父行会直接抛约束错误
            val existingCats = db.categoryDao().loadAll().associateBy { it.id }
            val newCatRows = snapshot.categories.mapNotNull { c ->
                if (existingCats.containsKey(c.id)) return@mapNotNull null
                CategoryEntity(
                    id = c.id,
                    nameCt = requireNotNull(
                        cipher.encryptString(c.id, FIELD_CATEGORY_NAME, c.name)
                    ),
                    icon = c.icon,
                    color = c.color,
                    sort = c.sort,
                    builtin = c.builtin
                )
            }
            if (newCatRows.isNotEmpty()) db.categoryDao().upsertAll(newCatRows)

            val validCatIds = existingCats.keys + newCatRows.map { it.id }
            val existingIds = db.entryDao().allIds().toSet()

            var added = 0
            var overwritten = 0
            var skipped = 0

            snapshot.entries.forEach { s ->
                val exists = s.id in existingIds
                if (exists && !overwrite) {
                    skipped++
                    return@forEach
                }

                val pwd = s.password
                val hasPwd = pwd != null && pwd.isNotEmpty()

                val entity = EntryEntity(
                    id = s.id,
                    // 指向一个不存在的分类会让外键约束整条插入失败，
                    // 所以这里要对着实际存在的分类做一次过滤
                    categoryId = s.categoryId?.takeIf { it in validCatIds },
                    titleCt = requireNotNull(cipher.encryptString(s.id, FIELD_TITLE, s.title)),
                    usernameCt = cipher.encryptString(s.id, FIELD_USERNAME, s.username),
                    passwordCt = if (hasPwd) cipher.encrypt(s.id, FIELD_PASSWORD, pwd) else null,
                    urlCt = cipher.encryptString(s.id, FIELD_URL, s.url),
                    notesCt = cipher.encryptString(s.id, FIELD_NOTES, s.notes),
                    totpCt = cipher.encryptString(s.id, FIELD_TOTP, s.totp),
                    // 优先用备份里存的原值：从原生 App 捕获的条目没有 url，
                    // 只有 packageNorm，从 url 重算会把它丢成 null
                    hostNorm = s.hostNorm ?: Normalize.hostFromUrl(s.url),
                    packageNorm = s.packageNorm,
                    favorite = s.favorite,
                    strengthScore = if (hasPwd) PasswordStrength.score(pwd!!) else 0,
                    pwdUpdatedAt = s.pwdUpdatedAt,
                    // 时间戳为 0 说明是别的版本导出的、没这个字段，用当前时间兜底，
                    // 否则列表里会出现一堆 1970 年的记录
                    createdAt = s.createdAt.takeIf { it > 0 } ?: now,
                    updatedAt = s.updatedAt.takeIf { it > 0 } ?: now,
                    lastUsedAt = s.lastUsedAt,
                    useCount = s.useCount,
                    deletedAt = s.deletedAt
                )

                db.entryDao().upsert(entity)

                // 自定义字段整体覆盖式写入：先清后写，
                // 这样备份里删掉的字段在本地也会消失
                db.customFieldDao().deleteForEntry(s.id)
                writeCustomFields(
                    s.id,
                    s.customFields.sortedBy { it.sort }.map {
                        // id 必须沿用备份里的：字段密文的 AAD 绑 `字段id|fieldName`，
                        // 换 id 等于让这份数据以后再也解不开
                        CustomFieldDraft(
                            id = it.id,
                            label = it.label,
                            value = it.value,
                            type = it.type
                        )
                    }
                )

                if (exists) overwritten++ else added++
            }

            val result = ImportResult(
                added = added,
                overwritten = overwritten,
                skipped = skipped,
                categoriesAdded = newCatRows.size
            )

            db.auditLogDao().insert(
                AuditLogEntity(
                    event = AuditLogEntity.EVENT_IMPORT,
                    targetId = null,
                    // detail 里只放数字，绝不能出现任何凭据
                    detail = "imported +$added ~$overwritten -$skipped",
                    createdAt = now
                )
            )
            db.auditLogDao().trim(AUDIT_KEEP)

            result
        }
    }

    /**
     * 从表格（CSV/TSV）导入条目。
     *
     * ── 为什么单独一条通路，不复用 [importSnapshot] ─────────────
     * 备份走的是"**id 必须原样搬**"的世界：分组、自定义字段的 AAD 都绑了 id，
     * 换 id 等于让数据以后解不开。表格里没有 id，也没有自定义字段，
     * 走快照那条路反而要伪造一堆 id 再让它们撞车。这里直接走 [createEntry]，
     * 加密、`hostNorm` 归一化、强度打分、`lastUsedAt` 初始化全都复用同一段逻辑，
     * 不会出现"导入的条目和手工建的条目行为不一样"。
     *
     * ── 去重规则 ────────────────────────────────────────────────
     * 按「标题 + 账号」判重（忽略大小写与首尾空格）。不用密码参与判重：
     * 同一个账号改过密码后再导一次，应该算"已存在"而不是"新条目"。
     * 判重只在本次导入开始时取一次快照，所以文件内部重复的行也会被拦住。
     *
     * @param skipExisting false 时全部当新条目插入（用户明确要求"再来一遍"）
     */
    suspend fun importCsv(
        rows: List<CsvRow>,
        skipExisting: Boolean = true
    ): ImportResult {
        val now = clock()
        val categoryByName = categories().associateBy { it.name.trim().lowercase() }.toMutableMap()
        val seenKeys = if (skipExisting) {
            loadSummaries().map { csvKeyOf(it.title, it.username) }.toMutableSet()
        } else {
            mutableSetOf()
        }

        var added = 0
        var skipped = 0
        var categoriesAdded = 0

        rows.forEach { row ->
            val key = csvKeyOf(row.title, row.username)
            if (skipExisting && !seenKeys.add(key)) {
                skipped++
                return@forEach
            }

            val categoryId = row.category?.let { rawName ->
                val name = rawName.trim()
                if (name.isEmpty()) {
                    null
                } else {
                    val existing = categoryByName[name.lowercase()]
                    if (existing != null) {
                        existing.id
                    } else {
                        createCategory(name)?.also { newId ->
                            categoriesAdded++
                            categoryByName[name.lowercase()] =
                                PlainCategory(newId, name, null, null, 0, false)
                        }
                    }
                }
            }

            val draft = EntryDraft(
                title = row.title,
                username = row.username,
                password = row.password,
                url = row.url,
                notes = row.notes,
                totp = row.totp,
                categoryId = categoryId
            )
            try {
                createEntry(draft)
                added++
            } catch (e: Exception) {
                // 单行失败不该让整次导入回滚：表格里常有一两行脏数据，
                // 用户要的是"能进的先进来"，剩下几条自己看提示补
                skipped++
            } finally {
                draft.clearSecrets()
            }
        }

        audit(
            AuditLogEntity.EVENT_IMPORT,
            null,
            // 与备份导入同一套格式，只放数字
            "csv +$added -$skipped ~$categoriesAdded"
        )

        return ImportResult(
            added = added,
            overwritten = 0,
            skipped = skipped,
            categoriesAdded = categoriesAdded
        )
    }

    /** 表格导入的判重键：标题 + 账号，忽略大小写与首尾空格。 */
    /**
     * 安全体检：全库解一遍密码，找弱密码、重复密码、超期没换的、压根没密码的。
     *
     * 全部在 IO 线程做，密码 CharArray 用完立刻填 0；比较重复用的是
     * contentHashCode 而不是把密码拼成 String —— 少一份明文副本在堆上。
     */
    suspend fun securityReport(now: Long = System.currentTimeMillis()): SecurityReport =
        withContext(Dispatchers.IO) {
            val rows = db.entryDao().loadActive()
            val issues = ArrayList<SecurityIssue>()
            val byHash = HashMap<Int, MutableList<Pair<String, String>>>()
            var withPassword = 0

            for (e in rows) {
                val title = cipher.decryptString(e.id, FIELD_TITLE, e.titleCt) ?: "(无法解密)"
                val username = cipher.decryptString(e.id, FIELD_USERNAME, e.usernameCt)
                val pwd = e.passwordCt?.let { cipher.decrypt(e.id, FIELD_PASSWORD, it) }
                if (pwd == null) {
                    issues += SecurityIssue(
                        e.id, title, username, SecurityIssueKind.NO_PASSWORD, "这条没存密码"
                    )
                    continue
                }
                try {
                    withPassword++
                    if (PasswordStrength.score(pwd) <= 1) {
                        issues += SecurityIssue(
                            e.id, title, username, SecurityIssueKind.WEAK, "短或太常见，容易被试出来"
                        )
                    }
                    val days = e.pwdUpdatedAt?.let { ((now - it) / 86_400_000L).toInt() }
                    if (days != null && days >= OLD_PASSWORD_DAYS) {
                        issues += SecurityIssue(
                            e.id, title, username, SecurityIssueKind.OLD, "已经 $days 天没换过"
                        )
                    }
                    byHash.getOrPut(pwd.contentHashCode()) { ArrayList() }.add(e.id to title)
                } finally {
                    pwd.fill('\u0000')
                }
            }

            for (group in byHash.values) {
                if (group.size < 2) continue
                for ((id, title) in group) {
                    val username = rows.firstOrNull { it.id == id }
                        ?.let { cipher.decryptString(it.id, FIELD_USERNAME, it.usernameCt) }
                    issues += SecurityIssue(
                        id, title, username, SecurityIssueKind.REUSED,
                        "和另外 ${group.size - 1} 个账号用同一个密码"
                    )
                }
            }

            SecurityReport(total = rows.size, withPassword = withPassword, issues = issues)
        }
    private fun csvKeyOf(title: String, username: String?): String =
        title.trim().lowercase() + "\u0001" + (username?.trim()?.lowercase() ?: "")

    // ── 内部 ──────────────────────────────────────────────────────

    private suspend fun writeCustomFields(entryId: String, drafts: List<CustomFieldDraft>) {
        if (drafts.isEmpty()) return
        val rows = drafts
            .filter { it.label.isNotBlank() || it.value.isNotBlank() }
            .mapIndexed { index, d ->
                val fid = d.id ?: Ids.newId()
                CustomFieldEntity(
                    id = fid,
                    entryId = entryId,
                    labelCt = requireNotNull(
                        cipher.encryptString(fid, FIELD_CF_LABEL, d.label)
                    ),
                    valueCt = requireNotNull(
                        cipher.encryptString(fid, FIELD_CF_VALUE, d.value)
                    ),
                    type = d.type,
                    sort = index
                )
            }
        if (rows.isNotEmpty()) db.customFieldDao().upsertAll(rows)
    }

    /**
     * 投影行 → 摘要。与 [toSummary] 逻辑一致，只是数据来源里没有 `notes_ct`。
     *
     * 失败分支同样返回占位记录：锁定瞬间密钥已被清零，解密会抛异常，
     * 让整个列表崩掉不如给一条"已锁定"（紧接着就会跳回解锁页）。
     */
    private fun toSummary(r: EntrySummaryRow): EntrySummary = try {
        EntrySummary(
            id = r.id,
            categoryId = r.categoryId,
            title = cipher.decryptString(r.id, FIELD_TITLE, r.titleCt) ?: "(无法解密)",
            username = cipher.decryptString(r.id, FIELD_USERNAME, r.usernameCt),
            url = cipher.decryptString(r.id, FIELD_URL, r.urlCt),
            hostNorm = r.hostNorm,
            packageNorm = r.packageNorm,
            favorite = r.favorite,
            strengthScore = r.strengthScore,
            pwdUpdatedAt = r.pwdUpdatedAt,
            createdAt = r.createdAt,
            updatedAt = r.updatedAt,
            lastUsedAt = r.lastUsedAt,
            useCount = r.useCount,
            hasPassword = r.hasPassword,
            hasTotp = r.hasTotp,
            deletedAt = r.deletedAt
        )
    } catch (ex: Exception) {
        EntrySummary(
            id = r.id,
            categoryId = r.categoryId,
            title = "(已锁定)",
            username = null,
            url = null,
            hostNorm = null,
            packageNorm = null,
            favorite = false,
            strengthScore = 0,
            pwdUpdatedAt = null,
            createdAt = r.createdAt,
            updatedAt = r.updatedAt,
            lastUsedAt = null,
            useCount = 0,
            hasPassword = false,
            hasTotp = false,
            deletedAt = r.deletedAt
        )
    }

    private fun toSummary(e: EntryEntity): EntrySummary = try {
        EntrySummary(
            id = e.id,
            categoryId = e.categoryId,
            title = cipher.decryptString(e.id, FIELD_TITLE, e.titleCt) ?: "(无法解密)",
            username = cipher.decryptString(e.id, FIELD_USERNAME, e.usernameCt),
            url = cipher.decryptString(e.id, FIELD_URL, e.urlCt),
            hostNorm = e.hostNorm,
            packageNorm = e.packageNorm,
            favorite = e.favorite,
            strengthScore = e.strengthScore,
            pwdUpdatedAt = e.pwdUpdatedAt,
            createdAt = e.createdAt,
            updatedAt = e.updatedAt,
            lastUsedAt = e.lastUsedAt,
            useCount = e.useCount,
            hasPassword = e.passwordCt != null,
            hasTotp = e.totpCt != null,
            deletedAt = e.deletedAt
        )
    } catch (ex: Exception) {
        // 锁定瞬间 cipher 的密钥已被清零，解出乱码或抛完整性异常。
        // 这里给一条占位记录而不是让整个列表崩掉 —— 反正紧接着就会跳回解锁页。
        EntrySummary(
            id = e.id,
            categoryId = e.categoryId,
            title = "(已锁定)",
            username = null,
            url = null,
            hostNorm = null,
            packageNorm = null,
            favorite = false,
            strengthScore = 0,
            pwdUpdatedAt = null,
            createdAt = e.createdAt,
            updatedAt = e.updatedAt,
            lastUsedAt = null,
            useCount = 0,
            hasPassword = false,
            hasTotp = false,
            deletedAt = e.deletedAt
        )
    }

    /**
     * 把一个域名展开成"自己 + 各级父域"。
     *
     * `new.taobao.com` → `[new.taobao.com, taobao.com, com]`。
     * 条目里存的是 eTLD+1，所以光用完整域名查会漏掉。
     * 末尾那个 `com` 查不到东西也无害 —— 不存在 host_norm = "com" 的条目。
     */
    private fun hostCandidates(host: String): List<String> {
        val out = ArrayList<String>(4)
        out += host
        var idx = host.indexOf('.')
        while (idx in 1 until host.length - 1) {
            out += host.substring(idx + 1)
            idx = host.indexOf('.', idx + 1)
        }
        return out
    }

    private fun matchesUsername(e: EntryEntity, username: String): Boolean = try {
        val stored = cipher.decryptString(e.id, FIELD_USERNAME, e.usernameCt)
        stored != null && SiteMatcher.sameUsername(stored, username)
    } catch (_: Exception) {
        false
    }

    companion object {
        /** 每个条目保留多少条密码历史 */
        const val HISTORY_KEEP = 20

        /** 审计日志上限 */
        const val AUDIT_KEEP = 2000

        /** 回收站保留期 */
        const val TRASH_RETENTION_MS: Long = 30L * 24 * 60 * 60 * 1000

        /**
         * 内置分类。三元组是 `(id, 显示名, icon)`。
         *
         * 顺序就是新库的初始排序，按"日常打开频率"排：
         * 登录类最多，其次是支付/购物，最后是证件、服务器这类低频项。
         *
         * **id 一旦定下就不要改**（它是 name 密文 AAD 的一半），
         * 显示名可以随便改 —— 名字变了密文跟着重写就行。
         *
         * 想加新分类：往这个列表里加一行，老库下次打开会自动补上。
         *
         * 2026-11 扩充：原来 16 个只覆盖了"登录 / 银行 / 证件"这条主线，用户
         * 反馈"分类的选择可以更多点"。新增的都是日常真的会记账的类别 ——
         * 工作、学校、医院、政务、快递、出门在外的车票酒店、家里的路由器与
         * 门锁、还有订阅和加密资产这类新东西。
         *
         * 新条目一律追加在末尾，**不插队**：`seedBuiltinCategoriesIfNeeded`
         * 给新分类分配的 sort 是"当前最大值 +1"，插在中间会让老库的补种顺序
         * 和列表顺序对不上（老库不会重排，只有新库按这里的次序来）。
         */
        private val BUILTIN_CATEGORIES = listOf(
            Triple("builtin_login", "登录", "login"),
            Triple("builtin_email", "邮箱", "email"),
            Triple("builtin_social", "社交", "social"),
            Triple("builtin_shopping", "购物", "cart"),
            Triple("builtin_pay", "支付", "pay"),
            Triple("builtin_bank", "银行", "bank"),
            Triple("builtin_card", "银行卡", "card"),
            Triple("builtin_member", "会员卡", "member"),
            Triple("builtin_game", "游戏", "game"),
            Triple("builtin_id", "证件", "id"),
            Triple("builtin_insurance", "保险", "shield"),
            Triple("builtin_vehicle", "车辆", "car"),
            Triple("builtin_wifi", "Wi-Fi", "wifi"),
            Triple("builtin_server", "服务器", "server"),
            Triple("builtin_note", "笔记", "note"),
            Triple("builtin_other", "其他", "more"),
            // ── 1.5 追加 ─────────────────────────────────────────
            Triple("builtin_work", "工作", "work"),
            Triple("builtin_study", "学校", "study"),
            Triple("builtin_forum", "论坛", "forum"),
            Triple("builtin_video", "影音", "video"),
            Triple("builtin_music", "音乐", "music"),
            Triple("builtin_cloud", "网盘", "cloud"),
            Triple("builtin_food", "外卖", "food"),
            Triple("builtin_travel", "出行", "travel"),
            Triple("builtin_hotel", "酒店", "hotel"),
            Triple("builtin_express", "快递", "express"),
            Triple("builtin_medical", "医疗", "medical"),
            Triple("builtin_gov", "政务", "gov"),
            Triple("builtin_subscription", "订阅", "subscription"),
            Triple("builtin_router", "路由器", "router"),
            Triple("builtin_crypto", "加密资产", "crypto"),
            Triple("builtin_school", "教育", "school"),
            Triple("builtin_family", "家庭", "family"),
            Triple("builtin_device", "设备", "device")
        )

        // AAD 里的字段名。改这里的字符串等于让旧数据全部解不开 —— 绝不要动。
        const val FIELD_TITLE = "title"
        const val FIELD_USERNAME = "username"
        const val FIELD_PASSWORD = "password"
        const val FIELD_URL = "url"
        const val FIELD_NOTES = "notes"
        const val FIELD_TOTP = "totp"
        const val FIELD_CATEGORY_NAME = "name"
        const val FIELD_CF_LABEL = "cf_label"
        const val FIELD_CF_VALUE = "cf_value"
    }
}
