package com.yg.vaultbook.data

import com.yg.vaultbook.core.crypto.FieldCrypto
import com.yg.vaultbook.core.crypto.TamperedDataException
import com.yg.vaultbook.core.keystore.DeviceKeyUnavailableException
import com.yg.vaultbook.core.matcher.CaptureConfidence
import com.yg.vaultbook.core.util.Ids
import com.yg.vaultbook.data.dao.DismissRuleDao
import com.yg.vaultbook.data.dao.PendingCaptureDao
import com.yg.vaultbook.data.dao.SiteMappingDao
import com.yg.vaultbook.data.entity.DismissRuleEntity
import com.yg.vaultbook.data.entity.PendingCaptureEntity
import com.yg.vaultbook.data.entity.SiteMappingEntity
import com.yg.vaultbook.domain.CaptureInput
import com.yg.vaultbook.domain.CaptureRecordResult
import com.yg.vaultbook.domain.EntryDraft
import com.yg.vaultbook.domain.PendingCaptureSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * 待确认捕获的仓储。
 *
 * ── 它和 [VaultRepository] 的关键差别 ───────────────────────
 * 本类用 [FieldCrypto] 接口而不是直接用 DEK 版实现，因为捕获发生在**锁定状态**：
 * 用户点浏览器"登录"的那一刻，本 App 的保险库是锁着的，没有 DEK。
 * 传入的 `crypto` 是 Keystore 版（见 `KeystoreFieldCipher`），不需要主密码。
 *
 * 用户点保存时才会把明文交给 [VaultRepository]，由后者用 DEK 重新加密写进正式表，
 * 然后这条待确认记录被删除 —— **Keystore 密文不会长期留存**。
 */
class CaptureRepository(
    private val pendingDao: PendingCaptureDao,
    private val mappingDao: SiteMappingDao,
    private val dismissDao: DismissRuleDao,
    private val crypto: FieldCrypto,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /**
     * 把「锁定时写进文件的捕获」搬进表。
     *
     * 这些行的密文本来就是 Keystore 通道加密的（AAD 口径与本类完全一致），
     * 所以不需要解密再重加密 —— 原样插入即可，代价接近一次批量写。
     *
     * 过期的直接丢：`expiresAt` 已经过去的记录用户永远不会去处理，
     * 留在表里只会让"待确认"计数虚高。
     *
     * @return 实际插入的条数
     */
    suspend fun importRows(rows: List<PendingCaptureEntity>): Int {
        if (rows.isEmpty()) return 0
        val now = clock()
        var inserted = 0
        for (row in rows) {
            if (row.expiresAt <= now) continue
            pendingDao.upsert(row)
            inserted++
        }
        return inserted
    }

    /**
     * 落一条捕获。
     *
     * 去重规则：同一 host 在 [DEDUP_WINDOW_MS] 内只保留一条待确认记录，
     * 后到的密码覆盖先到的（用户第一次输错、第二次输对，应该只看到对的那条）。
     * 复用同一行的 id 很重要 —— 密文的 AAD 绑了 `row.id`，换 id 旧密文立刻失效。
     */
    suspend fun record(input: CaptureInput): CaptureRecordResult {
        val pwd = input.password
        if (pwd == null || pwd.isEmpty()) {
            return CaptureRecordResult.Skipped("没有密码")
        }
        if (input.confidence < CaptureConfidence.PENDING_THRESHOLD) {
            return CaptureRecordResult.Skipped("置信度过低(${input.confidence})")
        }

        val now = clock()
        val host = input.hostNorm
        val pkg = input.packageNorm
        if (host != null && isSnoozedInternal(host, now)) {
            return CaptureRecordResult.Skipped("该站点已被静默")
        }

        // 去重键：有域名按域名，没有就按包名。
        // 原生 App（无障碍通道的主要场景）没有域名，只按 host 去重会完全失效。
        val existing = when {
            host != null -> pendingDao.latestPendingForHost(host)
            pkg != null -> pendingDao.latestPendingForPackage(pkg)
            else -> null
        }
        val reusable = existing?.takeIf { now - it.createdAt <= DEDUP_WINDOW_MS }
        val id = reusable?.id ?: Ids.newId()

        val row = PendingCaptureEntity(
            id = id,
            hostNorm = host,
            packageNorm = input.packageNorm,
            titleCt = encryptString(id, FIELD_TITLE, input.title),
            usernameCt = encryptString(id, FIELD_USERNAME, input.username),
            passwordCt = crypto.encrypt(id, FIELD_PASSWORD, pwd),
            confidence = input.confidence,
            source = input.source,
            matchedEntryId = input.matchedEntryId,
            state = PendingCaptureEntity.STATE_PENDING,
            // 复用时保留首次捕获时间与到期时间，避免"每输错一次就续命 7 天"
            createdAt = reusable?.createdAt ?: now,
            expiresAt = (reusable?.createdAt ?: now) + PendingCaptureEntity.TTL_MS
        )
        pendingDao.upsert(row)

        return if (reusable != null) {
            CaptureRecordResult.Updated(id)
        } else {
            CaptureRecordResult.Added(id)
        }
    }

    /** 待确认列表（不含密码）。解密失败的记录会被跳过并清理，绝不抛异常到 UI。 */
    fun observePending(): Flow<List<PendingCaptureSummary>> =
        pendingDao.observePending()
            .map { rows -> rows.mapNotNull { toSummary(it) } }
            .flowOn(Dispatchers.IO)

    suspend fun pending(): List<PendingCaptureSummary> =
        pendingDao.loadPending().mapNotNull { toSummary(it) }

    suspend fun countPending(): Int = pendingDao.countPending()

    /** 读出密码。调用方负责 `fill('\u0000')`。 */
    suspend fun revealPassword(id: String): CharArray? {
        val row = pendingDao.findById(id) ?: return null
        return try {
            crypto.decrypt(id, FIELD_PASSWORD, row.passwordCt)
        } catch (e: TamperedDataException) {
            null
        } catch (e: DeviceKeyUnavailableException) {
            null
        }
    }

    /**
     * 接受一条捕获，转成正式条目（或更新已有条目的密码）。
     *
     * **必须在解锁状态下调用** —— 它需要 [VaultRepository]，也就是需要 DEK。
     * 成功后删除待确认记录：内容已经用 DEK 重新加密进 `entries`，旧密文没有保留价值。
     *
     * @return 受影响的条目 id；记录不存在或无法解密时返回 null
     */
    suspend fun accept(id: String, target: VaultRepository): String? {
        val row = pendingDao.findById(id) ?: return null
        val now = clock()

        val plainPassword = try {
            crypto.decrypt(id, FIELD_PASSWORD, row.passwordCt)
        } catch (e: TamperedDataException) {
            null
        } catch (e: DeviceKeyUnavailableException) {
            null
        }
        // CharArray 没有 isNullOrEmpty()（那是 CharSequence 的扩展），
        // 而且这里需要一个**非空**的局部引用，后面 replacePassword / createEntry
        // 都要求 CharArray 而不是 CharArray?，所以在这里就地收窄类型。
        if (plainPassword == null || plainPassword.isEmpty()) {
            // 密钥已作废（用户改过锁屏密码）或密文坏了：这条救不回来，删掉别留着占位
            pendingDao.delete(id)
            return null
        }

        val username = decryptString(id, FIELD_USERNAME, row.usernameCt)
        val title = decryptString(id, FIELD_TITLE, row.titleCt)
        val matched = row.matchedEntryId

        val entryId = try {
            if (matched != null && target.loadDetail(matched) != null) {
                // 用户是在一个已知条目上登录的（比如改了密码）：只替换密码，其余字段别动
                target.replacePassword(matched, plainPassword)
                matched
            } else {
                target.createEntry(
                    EntryDraft(
                        title = title?.takeIf { it.isNotBlank() }
                            ?: row.hostNorm
                            ?: "未命名",
                        username = username?.takeIf { it.isNotBlank() },
                        password = plainPassword,
                        // 由 url 反推 host_norm，这样下次同站点能直接匹配到这条
                        url = row.hostNorm?.let { "https://$it" }
                    )
                )
            }
        } finally {
            plainPassword.fill('\u0000')
        }

        linkMapping(row.hostNorm, row.packageNorm, entryId, now)
        pendingDao.delete(id)
        return entryId
    }

    /** 丢掉一条，不保存。顺便删掉密文 —— 留着没有用途，只增加泄露面。 */
    suspend fun dismiss(id: String) {
        pendingDao.delete(id)
    }

    /** "这个站点别再问我了"，默认 24 小时。 */
    suspend fun snooze(host: String, hours: Int = DEFAULT_SNOOZE_HOURS) {
        dismissDao.upsert(
            DismissRuleEntity(hostNorm = host, untilAt = clock() + hours * 60L * 60L * 1000L)
        )
    }

    suspend fun isSnoozed(host: String): Boolean = isSnoozedInternal(host, clock())

    /** 顺带清理过期数据。解锁后或 App 启动时调一次即可。 */
    suspend fun purgeExpired(): Int {
        val now = clock()
        dismissDao.purgeExpired(now)
        return pendingDao.purgeExpired(now)
    }

    // ── 内部 ────────────────────────────────────────────────────

    private suspend fun isSnoozedInternal(host: String, now: Long): Boolean {
        val until = dismissDao.untilAt(host) ?: return false
        return until > now
    }

    /**
     * 把捕获和条目关联起来（`site_mappings`）。
     *
     * 权重给 3 而不是 1：这次关联来自"用户真的在这个站点登录过并点了保存"，
     * 比手动选择一次的证据更强。
     *
     * 必须先查再决定：`(host_norm, entry_id)` 有唯一索引，
     * 用新 id 直接 upsert 会撞唯一约束崩掉。
     */
    private suspend fun linkMapping(
        host: String?,
        packageName: String?,
        entryId: String,
        now: Long
    ) {
        val existing = if (host != null) {
            mappingDao.byHost(host).firstOrNull { it.entryId == entryId }
        } else {
            null
        }

        if (existing != null) {
            mappingDao.bump(existing.id, MAPPING_WEIGHT_BUMP, now)
        } else {
            mappingDao.upsert(
                SiteMappingEntity(
                    id = Ids.newId(),
                    hostNorm = host,
                    packageNorm = packageName,
                    entryId = entryId,
                    weight = MAPPING_INITIAL_WEIGHT,
                    lastUsedAt = now
                )
            )
        }
    }

    private suspend fun toSummary(row: PendingCaptureEntity): PendingCaptureSummary? {
        // 过期的不展示。DAO 的 SQL 里没带时间条件（Flow 长期订阅会把 now 写死），
        // 所以过滤放在这里，用注入的时钟。
        if (row.expiresAt <= clock()) return null

        val title: String?
        val username: String?
        try {
            title = decryptString(row.id, FIELD_TITLE, row.titleCt)
            username = decryptString(row.id, FIELD_USERNAME, row.usernameCt)
        } catch (e: TamperedDataException) {
            pendingDao.delete(row.id)
            return null
        } catch (e: DeviceKeyUnavailableException) {
            // 用户改过锁屏密码，密钥作废。这批待确认记录全部救不回来，清掉。
            pendingDao.delete(row.id)
            return null
        }

        return PendingCaptureSummary(
            id = row.id,
            hostNorm = row.hostNorm,
            packageNorm = row.packageNorm,
            title = title,
            username = username,
            confidence = row.confidence,
            source = row.source,
            matchedEntryId = row.matchedEntryId,
            createdAt = row.createdAt,
            hasPassword = row.passwordCt != null && row.passwordCt.isNotEmpty()
        )
    }

    private fun encryptString(id: String, field: String, value: String?): ByteArray? {
        if (value.isNullOrBlank()) return null
        val chars = value.toCharArray()
        return try {
            crypto.encrypt(id, field, chars)
        } finally {
            chars.fill('\u0000')
        }
    }

    private fun decryptString(id: String, field: String, blob: ByteArray?): String? {
        val chars = crypto.decrypt(id, field, blob) ?: return null
        return try {
            String(chars)
        } finally {
            chars.fill('\u0000')
        }
    }

    companion object {
        /** 同一站点在这个窗口内的多次捕获视为同一次登录 */
        const val DEDUP_WINDOW_MS: Long = 5L * 60 * 1000

        const val DEFAULT_SNOOZE_HOURS = 24
        private const val MAPPING_INITIAL_WEIGHT = 3
        private const val MAPPING_WEIGHT_BUMP = 3

        // AAD 字段名：pending 表与 entries 表用同样的名字。
        // 两条通道密钥不同，密文本来就不能互换，无需靠名字区分。
        private const val FIELD_TITLE = "title"
        private const val FIELD_USERNAME = "username"
        private const val FIELD_PASSWORD = "password"
    }
}
