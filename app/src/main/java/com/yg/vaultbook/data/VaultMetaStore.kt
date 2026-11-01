package com.yg.vaultbook.data

import android.content.Context
import android.util.Log
import com.yg.vaultbook.core.crypto.KdfParams
import com.yg.vaultbook.core.crypto.VaultMetaRecord
import java.util.Base64

/**
 * 保险库元数据的持久化。
 *
 * ── 为什么不用 Room 存这张表 ────────────────────────────────────
 * 存在一个鸡生蛋问题：打开 SQLCipher 数据库需要 `dbKey`，
 * 而 `dbKey` 被 DEK 包裹后存在元数据里。元数据如果也放在加密库里，
 * 就永远打不开。
 *
 * 所以元数据必须落在**加密库之外**。放 SharedPreferences 是安全的，
 * 因为这个文件里**没有任何明文密钥**：DEK 被 KEK 包裹、dbKey 被 DEK 包裹、
 * 校验串被 DEK 加密。拿到这个 xml 文件，不知道主密码就什么也做不了。
 *
 * ── 为什么用 commit() 而不是 apply() ───────────────────────────
 * 改主密码时，"新元数据已落盘"和"旧密码已失效"必须是同一件事。
 * apply() 是异步的，进程在这中间被杀掉，就会出现"内存里以为改成功了，
 * 磁盘上还是旧包裹块"的状态。commit() 是同步的，失败会返回 false，
 * 调用方必须检查。
 */
class VaultMetaStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** 是否已经初始化过保险库。首次启动为 false，走"设置主密码"流程。 */
    fun exists(): Boolean =
        prefs.contains(KEY_KDF_PARAMS) && prefs.contains(KEY_WRAPPED_DEK_MASTER)

    /**
     * 读取元数据。
     * @throws IllegalStateException 文件缺失或字段损坏 —— 调用方应引导用户走"从备份恢复"，
     *         绝不能静默新建一个保险库覆盖掉（那等于把用户的全部数据弄丢）
     */
    fun load(): VaultMetaRecord {
        check(exists()) { "保险库元数据不存在" }

        val kdfEncoded = prefs.getString(KEY_KDF_PARAMS, null)
            ?: throw IllegalStateException("元数据缺失：kdf_params")
        val wrappedDekMaster = prefs.getString(KEY_WRAPPED_DEK_MASTER, null)?.let(::b64d)
            ?: throw IllegalStateException("元数据缺失：wrapped_dek_master")
        val wrappedDbKey = prefs.getString(KEY_WRAPPED_DB_KEY, null)?.let(::b64d)
            ?: throw IllegalStateException("元数据缺失：wrapped_db_key")
        val masterVerifier = prefs.getString(KEY_MASTER_VERIFIER, null)?.let(::b64d)
            ?: throw IllegalStateException("元数据缺失：master_verifier")

        return VaultMetaRecord(
            kdf = KdfParams.decode(kdfEncoded),
            wrappedDekMaster = wrappedDekMaster,
            wrappedDekDevice = prefs.getString(KEY_WRAPPED_DEK_DEVICE, null)?.let(::b64d),
            wrappedDbKey = wrappedDbKey,
            masterVerifier = masterVerifier,
            createdAt = prefs.getLong(KEY_CREATED_AT, 0L),
            updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L),
            schemaVersion = prefs.getInt(KEY_SCHEMA_VERSION, VaultMetaRecord.SCHEMA_VERSION)
        )
    }

    /**
     * 写入元数据。
     * @return false 表示落盘失败，调用方**必须**当作操作失败处理并提示用户
     */
    fun save(meta: VaultMetaRecord): Boolean {
        val editor = prefs.edit()
            .putString(KEY_KDF_PARAMS, meta.kdf.encode())
            .putString(KEY_WRAPPED_DEK_MASTER, b64e(meta.wrappedDekMaster))
            .putString(KEY_WRAPPED_DB_KEY, b64e(meta.wrappedDbKey))
            .putString(KEY_MASTER_VERIFIER, b64e(meta.masterVerifier))
            .putLong(KEY_CREATED_AT, meta.createdAt)
            .putLong(KEY_UPDATED_AT, meta.updatedAt)
            .putInt(KEY_SCHEMA_VERSION, meta.schemaVersion)

        val device = meta.wrappedDekDevice
        if (device == null) {
            editor.remove(KEY_WRAPPED_DEK_DEVICE)
        } else {
            editor.putString(KEY_WRAPPED_DEK_DEVICE, b64e(device))
        }

        val ok = editor.commit()
        if (!ok) Log.e(TAG, "元数据落盘失败")
        return ok
    }

    /** 仅供"删除保险库"使用。正常流程绝不调用。 */
    fun clear(): Boolean = prefs.edit().clear().commit()

    private fun b64e(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun b64d(text: String): ByteArray =
        try {
            Base64.getDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("元数据损坏：Base64 解码失败", e)
        }

    companion object {
        private const val TAG = "VaultMetaStore"

        /** 单独一个文件，便于"删除保险库"时精确清理 */
        const val FILE_NAME = "vault_meta"

        private const val KEY_KDF_PARAMS = "kdf_params"
        private const val KEY_WRAPPED_DEK_MASTER = "wrapped_dek_master"
        private const val KEY_WRAPPED_DEK_DEVICE = "wrapped_dek_device"
        private const val KEY_WRAPPED_DB_KEY = "wrapped_db_key"
        private const val KEY_MASTER_VERIFIER = "master_verifier"
        private const val KEY_CREATED_AT = "created_at"
        private const val KEY_UPDATED_AT = "updated_at"
        private const val KEY_SCHEMA_VERSION = "schema_version"
    }
}
