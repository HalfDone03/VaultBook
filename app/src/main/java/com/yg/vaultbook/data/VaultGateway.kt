package com.yg.vaultbook.data

import android.content.Context
import com.yg.vaultbook.core.crypto.FieldCipher
import com.yg.vaultbook.core.crypto.FieldCrypto
import com.yg.vaultbook.core.crypto.KeyManager
import com.yg.vaultbook.core.crypto.PlainFieldCrypto
import com.yg.vaultbook.core.crypto.VaultKeys
import com.yg.vaultbook.core.crypto.VaultSession
import com.yg.vaultbook.core.keystore.DeviceKeyStore
import com.yg.vaultbook.core.keystore.KeystoreFieldCipher
import com.yg.vaultbook.core.simple.SimpleKeyStore
import com.yg.vaultbook.data.entity.AuditLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * 保险库生命周期编排：**密钥 → 数据库 → 仓储** 三者的唯一组装点。
 *
 * 为什么需要这一层：`KeyManager` 只负责算密钥，`VaultDatabase` 只负责开库，
 * `VaultRepository` 只负责读写。谁先谁后、失败怎么回滚、锁定怎么收尾，
 * 必须有一个地方统一定义，否则会出现"库打开了但 session 还是锁定"
 * 或者"session 解锁了但库没开"这类只有真机上才暴露的半状态。
 *
 * 全程 `Dispatchers.IO`：PBKDF2 210k 迭代在主线程会 ANR（约 0.2–1 秒，
 * 低端机更久），SQLCipher 首次建库还要做页初始化。
 *
 * ── W2 追加：两条加密通道并存 ────────────────────────────────
 * 保险库锁定时没有 DEK，但用户此刻正在别处登录网站 —— 捕获不能因为锁着就丢掉。
 * 所以这里额外持有一把**设备密钥**（[DeviceKeyStore]，受设备锁屏保护，
 * 不需要主密码），只用于"待确认捕获"这一小块数据：
 * - 锁定中：捕获写进 [captureQueue] 的独立文件；
 * - 解锁后：立刻搬进 `pending_captures` 表（见 [openInternal] 里的 drain），
 *   文件随即删除，Keystore 密文不留长期副本。
 * 正式条目（`entries`）永远只由主密码解封的 DEK 加密，设备密钥碰不到它们。
 */
class VaultGateway(
    private val context: Context,
    private val session: VaultSession,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val keyManager: KeyManager = KeyManager()
) {

    private val metaStore = VaultMetaStore(context)

    private val deviceStore = DeviceKeyStore(context)

    /** 设备密钥通道，锁定时可用。**只给待确认捕获用，不要拿它加解密正式条目。** */
    val keystoreCipher: KeystoreFieldCipher = KeystoreFieldCipher(deviceStore)

    /**
     * 简化模式的口令管理（无主密码）。
     *
     * 见 [SimpleKeyStore] 类注释：一次生成、永久复用、用户无感。
     */
    private val simpleKeyStore: SimpleKeyStore = SimpleKeyStore(context)

    /**
     * 简化模式的字段编解码：明文直通。
     *
     * 见 [PlainFieldCrypto] 类注释：这个 App 的定位是"比记事本顺手的速查本"，
     * 不是零知识保险库，所以不做字段级加密，也不再要求输入密码。
     */
    private val plainCipher: FieldCrypto = PlainFieldCrypto()

    /** 锁定期间的捕获缓冲，任何时候都能写，不依赖解锁。 */
    val captureQueue: KeystoreCaptureQueue = KeystoreCaptureQueue(context, plainCipher, clock)

    @Volatile
    private var database: VaultDatabase? = null

    @Volatile
    private var repository: VaultRepository? = null

    @Volatile
    private var captures: CaptureRepository? = null

    /** 是否已经设置过主密码。用于冷启动时二选一：初始化页 / 解锁页。 */
    fun hasVault(): Boolean = metaStore.exists()

    fun repositoryOrNull(): VaultRepository? = repository

    /** 待确认捕获仓储。锁定期间为 null —— 表在加密库里，锁着就打不开。 */
    fun captureRepositoryOrNull(): CaptureRepository? = captures

    /**
     * 简化模式开库：**不需要任何密码，打开就能用**。
     *
     * 与 [createVault] / [unlock] 的区别：
     * - 口令不是从用户密码派生的，而是 [SimpleKeyStore] 里那份随机 32 字节；
     * - 字段不走 AES，走 [PlainFieldCrypto]（明文 UTF-8）；
     * - 全程没有"锁定"概念，会话一开就一直是 UNLOCKED。
     *
     * 幂等：已经开着就直接返回成功。UI 在每次进入列表页前调一次不会重复开库。
     *
     * 失败面：
     * - [com.yg.vaultbook.core.simple.DbKeyMissingException] —— 库文件在、口令文件不在
     * - SQLCipher 打开失败 —— 库文件损坏或口令不匹配
     */
    suspend fun openSimple(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (repository != null) return@runCatching Unit

            val dbKey = simpleKeyStore.acquire()
            // 简化模式没有 DEK 概念，但 VaultKeys 需要它占位；
            // 随机填一把，宿主代码永远不会拿它加解密任何东西。
            val placeholderDek = ByteArray(DEK_PLACEHOLDER_BYTES)
                .also { SecureRandom().nextBytes(it) }
            val keys = VaultKeys(placeholderDek, dbKey)

            openInternal(keys, plainCipher)
            repository?.seedBuiltinCategoriesIfNeeded()
            repository?.purgeExpiredTrash()
            Unit
        }.onFailure { closeCurrent() }
    }

    /** 简化模式下"重新开始"：抹掉口令与数据库文件。调用方要负责重新 [openSimple]。 */
    suspend fun resetSimpleVault(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            closeCurrent()
            simpleKeyStore.reset()
            Unit
        }
    }

    /**
     * 首次创建保险库。
     *
     * 失败回滚顺序很关键：元数据写盘失败时**必须**销毁已生成的密钥，
     * 否则内存里留着一把没有任何记录指向的密钥，而磁盘上什么都没留下，
     * 下次启动又变成"未初始化"状态 —— 用户会以为第一次设的密码不存在了。
     */
    suspend fun createVault(masterPassword: CharArray): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                check(!hasVault()) { "保险库已存在，请直接解锁" }

                val created = keyManager.createVault(masterPassword, clock())
                var keepKeys = false
                try {
                    if (!metaStore.save(created.meta)) {
                        throw IllegalStateException("主密码已生成，但元数据写入失败，请重试")
                    }
                    openInternal(created.keys)
                    keepKeys = true
                    repository?.seedBuiltinCategoriesIfNeeded()
                    repository?.audit(AuditLogEntity.EVENT_CREATE, null, "vault created")
                } finally {
                    if (!keepKeys) created.keys.destroy()
                }
                // 显式 Unit：块尾表达式是 `repository?.audit(...)`（Unit?），
                // 不加这行 runCatching 会推断成 Result<Unit?>，与签名要求的 Result<Unit> 不符
                Unit
            }.onFailure { closeCurrent() }
        }

    /**
     * 用主密码解锁。
     *
     * @return 失败时 `exceptionOrNull()` 可能是
     *   [com.yg.vaultbook.core.crypto.WrongPasswordException]（口令错）
     *   或 [com.yg.vaultbook.core.crypto.TamperedDataException]（元数据被改动）
     */
    suspend fun unlock(masterPassword: CharArray): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val meta = metaStore.load()
                val keys = keyManager.unlockWithPassword(masterPassword, meta)
                var keepKeys = false
                try {
                    openInternal(keys)
                    keepKeys = true
                    repository?.seedBuiltinCategoriesIfNeeded()
                    repository?.purgeExpiredTrash()
                    repository?.audit(AuditLogEntity.EVENT_UNLOCK_OK)
                } finally {
                    if (!keepKeys) keys.destroy()
                }
                Unit
            }.onFailure { closeCurrent() }
        }

    /**
     * 锁定：先关库，再销毁密钥。
     *
     * 顺序不能反。先销毁密钥的话，`close()` 期间 SQLCipher 可能还要用
     * 口令做最后的页写入/校验，会拿到一把已清零的密钥 —— 轻则抛异常，
     * 重则留下一个元数据页损坏的库文件。
     */
    fun lock() {
        closeCurrent()
        session.lock()
    }

    /** 供 UI 的"改主密码"用。 */
    suspend fun changeMasterPassword(
        oldPassword: CharArray,
        newPassword: CharArray
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val meta = metaStore.load()
            val changed = keyManager.changeMasterPassword(oldPassword, newPassword, meta, clock())
            // 新元数据落盘失败 → 旧密码仍然有效，不必销毁密钥，直接报错让用户重试
            if (!metaStore.save(changed.meta)) {
                throw IllegalStateException("新主密码写入失败，旧密码仍然有效，请重试")
            }
            openInternal(changed.keys)
            repository?.audit(AuditLogEntity.EVENT_UPDATE, null, "master password changed")
            Unit
        }.onFailure { closeCurrent() }
    }

    // ── 内部 ──────────────────────────────────────────────────────

    /**
     * 真正的开库动作。
     *
     * @param cipher 字段编解码器。主密码模式传 [FieldCipher]（AES-GCM），
     *   简化模式传 [plainCipher]（明文直通）。两种模式的密文格式不同，
     *   **不能混着读同一个库** —— 这也是为什么简化模式是自己一套口令，
     *   不会误开旧的主密码库。
     */
    private suspend fun openInternal(
        keys: VaultKeys,
        cipher: FieldCrypto = FieldCipher(keys.dek)
    ) {
        closeCurrent()

        val db = VaultDatabase.open(context, keys.dbKey)
        // 主动摸一次可写库：口令错误、文件损坏、SQLCipher .so 不匹配
        // 都在这里立刻炸出来，而不是等用户点进列表页才崩
        db.openHelper.writableDatabase

        database = db
        repository = VaultRepository(db, cipher, clock)
        captures = CaptureRepository(
            pendingDao = db.pendingCaptureDao(),
            mappingDao = db.siteMappingDao(),
            dismissDao = db.dismissRuleDao(),
            crypto = cipher,
            clock = clock
        )
        session.unlock(keys)

        // 库已经能用了，把锁定期间攒下的捕获搬进来。
        // 放在 unlock 之后：搬运失败不该让"解锁成功"变成失败，
        // 而且此刻 UI 已经在读库，晚一拍写入用户也看不出来。
        drainCaptureQueue()
    }

    /**
     * 把文件缓冲里的捕获搬进 `pending_captures` 表。
     *
     * 失败（磁盘读坏、行格式异常）只记日志不动状态：数据已经尽力保全，
     * 让解锁因此失败对用户来说是更坏的结果。
     */
    private suspend fun drainCaptureQueue() {
        val repo = captures ?: return
        if (captureQueue.count() == 0) return
        try {
            val rows = captureQueue.drain()
            if (rows.isNotEmpty()) repo.importRows(rows)
        } catch (_: Exception) {
            // 缓冲已由 drain 尽力清理，剩下的交给下次启动
        }
    }

    private fun closeCurrent() {
        val db = database
        database = null
        repository = null
        captures = null
        if (db != null) {
            try {
                db.close()
            } catch (_: Exception) {
                // 已经关掉/被系统回收，没有可做的补救，继续往下走
            }
        }
    }

    private companion object {
        /** 简化模式下 VaultKeys 需要一个 dek 字段占位，长度与真实 DEK 保持一致。 */
        const val DEK_PLACEHOLDER_BYTES: Int = 32
    }
}
