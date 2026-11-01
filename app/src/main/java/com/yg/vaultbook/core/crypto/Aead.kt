package com.yg.vaultbook.core.crypto

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM 封装，是本项目唯一的对称加密出口。
 *
 * 约定：
 * 1. **每次加密都新生成 12 字节随机 IV**。GCM 下 IV 重用会直接毁掉机密性与完整性，
 *    这是本类最重要的一条不变量，任何优化都不得改动。
 * 2. **AAD = 调用方 AAD ‖ version ‖ flags**。把 header 也纳入认证，
 *    使得攻击者无法在不被发现的前提下改写密文的版本号或标志位。
 * 3. 认证失败统一抛 [TamperedDataException]，不区分"密码错"和"被篡改"，
 *    避免通过错误信息做区分攻击。
 */
object Aead {

    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"

    private val random = SecureRandom()

    fun seal(key: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        requireKey(key)

        val iv = ByteArray(CryptoBlob.IV_BYTES)
        random.nextBytes(iv)

        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, KEY_ALGORITHM),
            GCMParameterSpec(CryptoBlob.TAG_BITS, iv)
        )
        cipher.updateAAD(bind(CryptoBlob.VERSION, CryptoBlob.FLAG_AAD_BOUND, aad))

        val payload = cipher.doFinal(plaintext)
        return try {
            CryptoBlob.pack(iv, payload, CryptoBlob.FLAG_AAD_BOUND)
        } finally {
            payload.fill(0)
            iv.fill(0)
        }
    }

    fun open(key: ByteArray, aad: ByteArray, blob: ByteArray): ByteArray {
        requireKey(key)

        val unpacked = CryptoBlob.unpack(blob)

        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, KEY_ALGORITHM),
            GCMParameterSpec(CryptoBlob.TAG_BITS, unpacked.iv)
        )
        cipher.updateAAD(bind(unpacked.version, unpacked.flags, aad))

        return try {
            cipher.doFinal(unpacked.payload)
        } catch (e: AEADBadTagException) {
            throw TamperedDataException("密文认证失败（密钥不匹配或数据被篡改）", e)
        } finally {
            unpacked.iv.fill(0)
            unpacked.payload.fill(0)
        }
    }

    /**
     * AAD 拼装：`调用方AAD ‖ version ‖ flags`。
     *
     * 暴露为 internal 是为了让 [com.yg.vaultbook.core.keystore.DeviceKeyStore] 用**完全相同**的
     * 拼法做 Keystore 加密 —— 两条通道共用一种 blob 格式，才能用同一套解析与校验代码。
     */
    internal fun bind(version: Byte, flags: Byte, aad: ByteArray): ByteArray {
        val out = ByteArray(aad.size + 2)
        System.arraycopy(aad, 0, out, 0, aad.size)
        out[aad.size] = version
        out[aad.size + 1] = flags
        return out
    }

    private fun requireKey(key: ByteArray) {
        require(key.size == 16 || key.size == 32) {
            "AES 密钥必须为 128 或 256 位，实际 ${key.size * 8} 位"
        }
    }
}

/** 全局统一的 AAD 常量，集中管理避免拼错。 */
internal object CryptoAad {

    val DEK_MASTER: ByteArray = "vb:dek:master".toByteArray(Charsets.UTF_8)
    val DEK_DEVICE: ByteArray = "vb:dek:device".toByteArray(Charsets.UTF_8)
    val DB_KEY: ByteArray = "vb:dbkey".toByteArray(Charsets.UTF_8)
    val VERIFY: ByteArray = "vb:verify".toByteArray(Charsets.UTF_8)

    /** 字段级加密：把 entryId 与字段名绑进 AAD，密文不可跨条目/跨字段搬运 */
    fun field(entryId: String, fieldName: String): ByteArray =
        "$entryId|$fieldName".toByteArray(Charsets.UTF_8)
}
