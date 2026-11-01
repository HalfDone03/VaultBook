package com.yg.vaultbook.core.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

enum class KdfAlgo(val id: String) {
    PBKDF2_SHA256("pbkdf2-sha256");

    companion object {
        fun fromId(id: String): KdfAlgo =
            values().firstOrNull { it.id == id }
                ?: throw CryptoFormatException("未知的 KDF 算法：$id")
    }
}

/**
 * KDF 参数。会以文本形式存进 `vault_meta.kdf_params`，必须做到：
 * 1. 自描述（升级算法/参数后老数据仍可解）；
 * 2. 解析时做**降级防御**——若参数被篡改成 iterations=1，绝不能默默接受。
 *
 * 不是 data class：里面含 ByteArray，data class 的 equals 是引用比较，
 * 很容易被误用成"参数相同"的判断。需要比较时用 [encode] 的字符串。
 */
class KdfParams(
    val algo: KdfAlgo,
    val iterations: Int,
    val salt: ByteArray,
    val keyLengthBits: Int = KEY_BITS
) {

    init {
        require(iterations > 0) { "iterations 必须为正数" }
        require(salt.isNotEmpty()) { "salt 不能为空" }
        require(keyLengthBits == 128 || keyLengthBits == 256) {
            "密钥长度必须为 128 或 256 位"
        }
    }

    fun encode(): String = buildString {
        append("algo=").append(algo.id)
        append(";iter=").append(iterations)
        append(";salt=").append(Base64.getEncoder().encodeToString(salt))
        append(";len=").append(keyLengthBits)
    }

    companion object {
        const val SALT_BYTES: Int = 32
        const val KEY_BITS: Int = 256

        /** 生产默认迭代次数，与文档 02 §3.2 一致 */
        const val DEFAULT_ITERATIONS: Int = 210_000

        /**
         * 解析来自持久化层的参数时的最低门槛。
         * 低于此值视为"被降级攻击"或数据损坏，直接拒绝，不尝试兼容。
         */
        const val MIN_ACCEPTED_ITERATIONS: Int = 100_000

        private val random = SecureRandom()

        fun newSalt(size: Int = SALT_BYTES): ByteArray =
            ByteArray(size).also { random.nextBytes(it) }

        fun newDefault(): KdfParams = KdfParams(
            algo = KdfAlgo.PBKDF2_SHA256,
            iterations = DEFAULT_ITERATIONS,
            salt = newSalt()
        )

        /** 从持久化字符串还原，并做降级防御校验 */
        fun decode(encoded: String): KdfParams {
            val map = encoded.split(';')
                .mapNotNull { pair ->
                    val idx = pair.indexOf('=')
                    if (idx <= 0) null else pair.substring(0, idx) to pair.substring(idx + 1)
                }
                .toMap()

            val algo = KdfAlgo.fromId(map["algo"] ?: throw CryptoFormatException("缺少 algo"))
            val iterations = map["iter"]?.toIntOrNull()
                ?: throw CryptoFormatException("缺少或非法的 iter")
            val salt = map["salt"]?.let {
                try {
                    Base64.getDecoder().decode(it)
                } catch (e: IllegalArgumentException) {
                    throw CryptoFormatException("salt 不是合法 Base64")
                }
            } ?: throw CryptoFormatException("缺少 salt")
            val len = map["len"]?.toIntOrNull() ?: KEY_BITS

            if (iterations < MIN_ACCEPTED_ITERATIONS) {
                throw CryptoFormatException(
                    "KDF 迭代次数异常偏低（$iterations < $MIN_ACCEPTED_ITERATIONS），拒绝使用"
                )
            }
            return KdfParams(algo, iterations, salt, len)
        }
    }
}

object Kdf {

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    /**
     * 派生密钥。
     *
     * @param password 主密码；调用方负责在返回后用自己的引用清零
     * @return 长度由 [KdfParams.keyLengthBits] 决定的密钥字节
     */
    fun derive(password: CharArray, params: KdfParams): ByteArray {
        if (password.isEmpty()) throw IllegalArgumentException("主密码不能为空")

        val spec = PBEKeySpec(
            password,          // PBEKeySpec 内部会复制一份，clearPassword 清的是副本
            params.salt,
            params.iterations,
            params.keyLengthBits
        )
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } catch (e: Exception) {
            throw IllegalStateException("KDF 派生失败", e)
        } finally {
            spec.clearPassword()
        }
    }
}
