package com.yg.vaultbook.core.simple

import android.content.Context
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * 简化模式的数据库口令管理：**一次生成，永久复用，用户无感**。
 *
 * ── 它解决什么 ───────────────────────────────────────────────
 * SQLCipher 必须有一个 32 字节口令才能开库。以前这个口令来自用户主密码
 * 派生，所以每次启动都要输密码。现在改成首次启动随机生成 32 字节，
 * 存在 SharedPreferences 里明文 base64，之后一辈子沿用 —— 用户再也不需要
 * 记住任何东西。
 *
 * ── 为什么明文存口令 ─────────────────────────────────────────
 * 因为它保护的不是"防止你自己看不到"，而是"防止别人把 vault.db 单独
 * 拷走直接读"。把 db 文件和 SharedPreferences 一起拷走当然能开 —— 但那
 * 已经不是"随手拷个文件"，而是针对性地搬数据。
 *
 * 反过来，如果把它塞进 Android Keystore：
 * - 用户清一次 App 数据、换个签名重装、或系统升级异常，key 就没了，
 *   库永久打不开 —— 对"日常速查"这个场景是不能接受的失败模式；
 * - 收益只是防住"能读 SharedPreferences 但读不到 Keystore"这种组合，
 *   现实中只要拿到前者，基本也拿到后者。
 *
 * 所以这里刻意选了"能用、丢不了"而不是"更安全、可能永久打不开"。
 *
 * ── 两种异常情况 ─────────────────────────────────────────────
 * 1. **库在、口令不在**：只拷了 `vault.db` 没拷 `shared_prefs/simple_vault.xml`。
 *    这时绝不新建口令（新建后打开旧库会报 "file is not a database"，
 *    用户会以为文件损坏）。[acquire] 抛 [DbKeyMissingException]，UI 直接
 *    告诉用户"还需要一起拷贝 simple_vault.xml"。
 * 2. **口令在、库不在**：正常首次使用，沿用口令即可。
 */
class SimpleKeyStore(context: Context) {

    private val appContext: Context = context.applicationContext

    private val prefs = appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val random = SecureRandom()

    /** 数据库文件是否已经存在（与口令是否存在独立判断）。 */
    fun databaseExists(): Boolean = File(databaseDir(), DB_FILE_NAME).exists()

    /** 当前是否已经有口令（不影响是否已有数据库）。 */
    fun hasKey(): Boolean = prefs.contains(KEY_DB_KEY)

    /**
     * 取得可用于开库的 32 字节口令。
     *
     * 首次调用会生成并落盘；后续调用都返回同一份。
     *
     * @throws DbKeyMissingException 数据库文件在、但口令不在
     */
    fun acquire(): ByteArray {
        val stored = prefs.getString(KEY_DB_KEY, null)
        if (stored != null) {
            val decoded = runCatching { Base64.getDecoder().decode(stored) }.getOrNull()
            if (decoded != null && decoded.size == KEY_BYTES) return decoded
            // 存了但解不出合法长度：只能当没有。此时若已有库文件，下面会抛
        }

        if (databaseExists()) {
            throw DbKeyMissingException(
                "数据库文件存在，但口令丢失。请把 shared_prefs 里的 $FILE_NAME.xml " +
                    "和数据库文件一起拷贝过来。"
            )
        }

        val fresh = ByteArray(KEY_BYTES).also { random.nextBytes(it) }
        prefs.edit().putString(KEY_DB_KEY, Base64.getEncoder().encodeToString(fresh)).apply()
        return fresh
    }

    /**
     * 清空口令与数据库文件（"重新开始"用）。
     *
     * 只删自己这个文件与 vault.db，不动同目录下其它东西。
     */
    fun reset() {
        prefs.edit().clear().apply()
        File(databaseDir(), DB_FILE_NAME).delete()
        File(databaseDir(), "$DB_FILE_NAME-wal").delete()
        File(databaseDir(), "$DB_FILE_NAME-shm").delete()
    }

    private fun databaseDir(): File = appContext.getDatabasePath(DB_FILE_NAME).parentFile
        ?: File(appContext.applicationInfo.dataDir, "databases")

    companion object {
        const val FILE_NAME: String = "simple_vault"

        /** 与 `VaultDatabase.FILE_NAME` 必须一致。 */
        const val DB_FILE_NAME: String = "vault.db"

        private const val KEY_DB_KEY: String = "db_key"

        /** SQLCipher 要求 32 字节口令（原始 key 形式）。 */
        const val KEY_BYTES: Int = 32
    }
}

/** 库文件在、口令不在。UI 应该直接告诉用户怎么补救，而不是当成"库损坏"。 */
class DbKeyMissingException(message: String) : IllegalStateException(message)
