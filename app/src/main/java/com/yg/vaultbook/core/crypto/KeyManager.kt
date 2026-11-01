package com.yg.vaultbook.core.crypto

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 内存中的密钥集合。**只在解锁期间存在**，锁定即 [destroy]。
 *
 * 这里刻意不做成 data class / 不加 copy：密钥是可变字节数组，
 * 任何"顺手复制一份"的行为都会延长密钥在内存中的寿命。
 */
class VaultKeys(val dek: ByteArray, val dbKey: ByteArray) {

    private var destroyed = false

    val isDestroyed: Boolean get() = destroyed

    /** 清零并作废。重复调用安全。 */
    fun destroy() {
        if (destroyed) return
        dek.fill(0)
        dbKey.fill(0)
        destroyed = true
    }
}

/**
 * 可持久化的保险库元数据（对应 `vault_meta` 单行）。
 *
 * 不含任何明文密钥：DEK 被 KEK 包裹、dbKey 被 DEK 包裹、
 * 校验串被 DEK 加密。拿到这个对象和数据库也无法还原明文密码，
 * 除非知道主密码或持有设备 Keystore 密钥。
 */
class VaultMetaRecord(
    val kdf: KdfParams,
    val wrappedDekMaster: ByteArray,
    val wrappedDekDevice: ByteArray?,
    val wrappedDbKey: ByteArray,
    val masterVerifier: ByteArray,
    val createdAt: Long,
    val updatedAt: Long,
    val schemaVersion: Int = SCHEMA_VERSION
) {

    fun copyWith(
        kdf: KdfParams = this.kdf,
        wrappedDekMaster: ByteArray = this.wrappedDekMaster,
        wrappedDekDevice: ByteArray? = this.wrappedDekDevice,
        wrappedDbKey: ByteArray = this.wrappedDbKey,
        masterVerifier: ByteArray = this.masterVerifier,
        createdAt: Long = this.createdAt,
        updatedAt: Long = this.updatedAt,
        schemaVersion: Int = this.schemaVersion
    ): VaultMetaRecord = VaultMetaRecord(
        kdf = kdf,
        wrappedDekMaster = wrappedDekMaster,
        wrappedDekDevice = wrappedDekDevice,
        wrappedDbKey = wrappedDbKey,
        masterVerifier = masterVerifier,
        createdAt = createdAt,
        updatedAt = updatedAt,
        schemaVersion = schemaVersion
    )

    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}

/**
 * 密钥包裹器：把 DEK 变成一个可安全落盘的密文块，并能解回去。
 *
 * 两种实现：
 * - [SecretKeyWrapper]：给定 32 字节 KEK（主密码派生结果，或测试用固定值）
 * - `KeystoreKeyWrapper`：W2 引入，密钥驻留 Android Keystore，
 *   可设置 `setUserAuthenticationRequired(true)` 绑定生物识别
 */
interface KeyWrapper {
    fun wrap(dek: ByteArray): ByteArray
    fun unwrap(blob: ByteArray): ByteArray
}

class SecretKeyWrapper(
    private val key: ByteArray,
    private val aad: ByteArray = CryptoAad.DEK_MASTER
) : KeyWrapper {

    override fun wrap(dek: ByteArray): ByteArray = Aead.seal(key, aad, dek)

    override fun unwrap(blob: ByteArray): ByteArray = Aead.open(key, aad, blob)
}

/** 保险库结构损坏，无法解密（与"密码错"区分开，UI 提示不同） */
class VaultCorruptedException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** [KeyManager.createVault] 的返回值：内存密钥 + 待持久化的元数据 */
class CreatedVault(val keys: VaultKeys, val meta: VaultMetaRecord)

/** [KeyManager.changeMasterPassword] 的返回值 */
class ChangedVault(val keys: VaultKeys, val meta: VaultMetaRecord)

/**
 * 密钥生命周期管理：初始化保险库、主密码解锁、改主密码、登记/使用设备密钥。
 *
 * 关键不变量：
 * 1. **DEK 在整个生命周期内不变**。改主密码只重算 KEK 并重新包裹 DEK，
 *    因此不需要重新加密任何一条记录 —— 几万条数据改密码也是毫秒级。
 * 2. **dbKey 也不变**。改主密码不动 SQLCipher 口令，避免重写整个数据库文件。
 * 3. 任何派生出来的中间密钥（KEK）用完立刻 `fill(0)`。
 *
 * 依赖注入的三个参数都是为了可测：单测里用低迭代 KdfParams 和固定 SecureRandom
 * 就能在毫秒级跑完整流程。
 */
class KeyManager(
    private val paramsFactory: () -> KdfParams = { KdfParams.newDefault() },
    private val derive: (CharArray, KdfParams) -> ByteArray = Kdf::derive,
    private val random: SecureRandom = SecureRandom()
) {

    /**
     * 初始化一个全新的保险库。
     * @param masterPassword 主密码，调用方负责事后清空自己的 CharArray
     */
    fun createVault(
        masterPassword: CharArray,
        now: Long = System.currentTimeMillis()
    ): CreatedVault {
        require(masterPassword.size >= MIN_MASTER_PASSWORD_LENGTH) {
            "主密码至少 $MIN_MASTER_PASSWORD_LENGTH 位"
        }

        val params = paramsFactory()
        val kek = derive(masterPassword, params)
        val dek = ByteArray(DEK_BYTES).also { random.nextBytes(it) }
        val dbKey = ByteArray(DB_KEY_BYTES).also { random.nextBytes(it) }

        try {
            val meta = VaultMetaRecord(
                kdf = params,
                wrappedDekMaster = Aead.seal(kek, CryptoAad.DEK_MASTER, dek),
                wrappedDekDevice = null,
                wrappedDbKey = Aead.seal(dek, CryptoAad.DB_KEY, dbKey),
                masterVerifier = Aead.seal(dek, CryptoAad.VERIFY, VERIFY_MARKER),
                createdAt = now,
                updatedAt = now
            )
            return CreatedVault(VaultKeys(dek, dbKey), meta)
        } finally {
            kek.fill(0)
        }
    }

    /**
     * 用主密码解锁。
     * @throws WrongPasswordException 主密码不正确
     * @throws VaultCorruptedException 元数据损坏（不是密码问题）
     */
    fun unlockWithPassword(masterPassword: CharArray, meta: VaultMetaRecord): VaultKeys {
        val kek = derive(masterPassword, meta.kdf)
        try {
            val dek = try {
                Aead.open(kek, CryptoAad.DEK_MASTER, meta.wrappedDekMaster)
            } catch (e: TamperedDataException) {
                // GCM 认证失败：最高概率是密码错，统一按密码错报
                throw WrongPasswordException()
            }

            try {
                verifyMarker(dek, meta.masterVerifier)
                val dbKey = Aead.open(dek, CryptoAad.DB_KEY, meta.wrappedDbKey)
                return VaultKeys(dek, dbKey)
            } catch (e: Throwable) {
                dek.fill(0)
                throw e
            }
        } finally {
            kek.fill(0)
        }
    }

    /**
     * 修改主密码。旧密码错误则抛异常且不产生任何副作用。
     *
     * @return 新的内存密钥（与旧 DEK 相同）与新的元数据；
     *         **必须把 [ChangedVault.meta] 落盘，否则旧密码仍可用**
     */
    fun changeMasterPassword(
        oldPassword: CharArray,
        newPassword: CharArray,
        meta: VaultMetaRecord,
        now: Long = System.currentTimeMillis()
    ): ChangedVault {
        require(newPassword.size >= MIN_MASTER_PASSWORD_LENGTH) {
            "新主密码至少 $MIN_MASTER_PASSWORD_LENGTH 位"
        }

        val oldKeys = unlockWithPassword(oldPassword, meta)   // 顺带验证旧密码

        try {
            val newParams = paramsFactory()
            val newKek = derive(newPassword, newParams)

            try {
                // 用同一个 DEK 重新包裹：DEK 与 dbKey 都不变
                val newMeta = meta.copyWith(
                    kdf = newParams,
                    wrappedDekMaster = Aead.seal(newKek, CryptoAad.DEK_MASTER, oldKeys.dek),
                    updatedAt = now
                )
                return ChangedVault(
                    keys = VaultKeys(oldKeys.dek.copyOf(), oldKeys.dbKey.copyOf()),
                    meta = newMeta
                )
            } finally {
                newKek.fill(0)
            }
        } finally {
            oldKeys.destroy()   // 复制已完成，清掉临时引用
        }
    }

    /** 登记设备密钥（生物识别路径）。返回需要落盘的新元数据。 */
    fun enrollDeviceKey(
        keys: VaultKeys,
        wrapper: KeyWrapper,
        meta: VaultMetaRecord,
        now: Long = System.currentTimeMillis()
    ): VaultMetaRecord = meta.copyWith(
        wrappedDekDevice = wrapper.wrap(keys.dek),
        updatedAt = now
    )

    /** 注销设备密钥（用户关闭生物识别解锁）。 */
    fun revokeDeviceKey(
        meta: VaultMetaRecord,
        now: Long = System.currentTimeMillis()
    ): VaultMetaRecord = meta.copyWith(
        wrappedDekDevice = null,
        updatedAt = now
    )

    /** 用设备密钥解锁。Keystore 密钥被系统作废时抛异常，调用方回落到主密码。 */
    fun unlockWithDeviceKey(wrapper: KeyWrapper, meta: VaultMetaRecord): VaultKeys {
        val wrapped = meta.wrappedDekDevice
            ?: throw IllegalStateException("未启用设备密钥解锁")

        val dek = try {
            wrapper.unwrap(wrapped)
        } catch (e: TamperedDataException) {
            throw VaultCorruptedException("设备密钥无法解封（可能已失效）", e)
        }

        try {
            verifyMarker(dek, meta.masterVerifier)
            val dbKey = Aead.open(dek, CryptoAad.DB_KEY, meta.wrappedDbKey)
            return VaultKeys(dek, dbKey)
        } catch (e: Throwable) {
            dek.fill(0)
            throw e
        }
    }

    // ── 内部 ─────────────────────────────────────────────────────

    private fun verifyMarker(dek: ByteArray, verifier: ByteArray) {
        val actual = try {
            Aead.open(dek, CryptoAad.VERIFY, verifier)
        } catch (e: TamperedDataException) {
            throw VaultCorruptedException("校验串无法解密", e)
        }
        try {
            // MessageDigest.isEqual 是常量时间比较，避免按字节提前返回泄漏信息
            if (!MessageDigest.isEqual(actual, VERIFY_MARKER)) {
                throw VaultCorruptedException("校验串不匹配")
            }
        } finally {
            actual.fill(0)
        }
    }

    companion object {
        const val DEK_BYTES: Int = 32
        const val DB_KEY_BYTES: Int = 32
        const val MIN_MASTER_PASSWORD_LENGTH: Int = 8

        private val VERIFY_MARKER: ByteArray = "VAULTBOOK-VERIFY-V1".toByteArray(Charsets.UTF_8)
    }
}
