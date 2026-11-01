package com.yg.vaultbook.core.crypto

/**
 * 密文包结构（所有落库的 BLOB 都是这个格式）：
 *
 * ```
 * offset 0     : 1B    version
 * offset 1     : 1B    flags       (bit0 = AAD 已绑定)
 * offset 2     : 12B   iv          (GCM nonce，每次加密随机)
 * offset 14    : N B   ciphertext
 * offset 14+N  : 16B   GCM tag
 * ```
 *
 * `Cipher.doFinal()` 在 GCM 模式下输出 = ciphertext || tag，
 * 所以 pack 只需把 header + 该输出拼接，unpack 时不做拆分。
 *
 * version 与 flags 会作为 AAD 的一部分参与认证（见 Aead），
 * 因此篡改这两个字节会导致解密失败，而不是被静默接受。
 */
object CryptoBlob {

    const val VERSION: Byte = 0x01

    /** 该包由调用方提供了 AAD，解密时必须提供同一 AAD */
    const val FLAG_AAD_BOUND: Byte = 0x01

    const val IV_BYTES: Int = 12

    const val TAG_BITS: Int = 128
    const val TAG_BYTES: Int = TAG_BITS / 8

    const val HEADER_BYTES: Int = 2 + IV_BYTES

    /** 最小合法长度：header + 空明文 + tag */
    const val MIN_BYTES: Int = HEADER_BYTES + TAG_BYTES

    class Unpacked(
        val version: Byte,
        val flags: Byte,
        val iv: ByteArray,
        /** ciphertext || tag，直接喂给 Cipher.doFinal */
        val payload: ByteArray
    )

    fun pack(iv: ByteArray, payload: ByteArray, flags: Byte = FLAG_AAD_BOUND): ByteArray {
        require(iv.size == IV_BYTES) { "IV 必须为 $IV_BYTES 字节，实际 ${iv.size}" }
        require(payload.size >= TAG_BYTES) { "payload 至少包含 $TAG_BYTES 字节认证标签" }

        val out = ByteArray(HEADER_BYTES + payload.size)
        out[0] = VERSION
        out[1] = flags
        System.arraycopy(iv, 0, out, 2, IV_BYTES)
        System.arraycopy(payload, 0, out, HEADER_BYTES, payload.size)
        return out
    }

    fun unpack(blob: ByteArray): Unpacked {
        if (blob.size < MIN_BYTES) {
            throw CryptoFormatException("密文长度非法：${blob.size} < $MIN_BYTES")
        }
        val version = blob[0]
        if (version != VERSION) {
            throw CryptoFormatException("不支持的密文版本：$version（期望 $VERSION）")
        }
        val flags = blob[1]
        val iv = blob.copyOfRange(2, HEADER_BYTES)
        val payload = blob.copyOfRange(HEADER_BYTES, blob.size)
        return Unpacked(version, flags, iv, payload)
    }
}

/** 密文结构 / 版本 / 算法参数不合法（数据损坏或来自不兼容版本） */
class CryptoFormatException(message: String) : RuntimeException(message)

/** 主密码错误（GCM 认证失败或校验串不匹配） */
class WrongPasswordException(message: String = "主密码不正确") : RuntimeException(message)

/** 密文完整性校验失败（被篡改、AAD 不匹配、DEK 不匹配） */
class TamperedDataException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
