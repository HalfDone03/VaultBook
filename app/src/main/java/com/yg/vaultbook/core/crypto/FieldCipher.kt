package com.yg.vaultbook.core.crypto

/**
 * 字段级加解密器。
 *
 * 设计要点：
 * - **AAD 绑定 `entryId|fieldName`**：entryA 的密码密文不能被挪到 entryB 的密码字段上
 *   使用（会认证失败），也不能被挪到同一条目的"备注"字段上。这挡掉了"换列攻击"。
 * - 每个字段独立 IV，因此同一明文在同一库里出现两次也不会产生相同密文，
 *   无法通过比对密文判断"这两个账号是不是同一个密码"。
 * - 空值返回 `null` 而不是"空密文"，节省空间且语义清晰。
 *
 * 线程安全：本类无可变状态；底层 `Cipher` 每次新建，`SecureRandom` 线程安全。
 * 可被多个协程并发调用。
 */
class FieldCipher(private val dek: ByteArray) : FieldCrypto {

    init {
        require(dek.size == 32) { "字段加密要求 256 位 DEK，实际 ${dek.size * 8} 位" }
    }

    /**
     * 加密一段字符。
     * @return null 表示"该字段不存在"；调用方无需区分空串与 null
     */
    override fun encrypt(entryId: String, fieldName: String, plaintext: CharArray?): ByteArray? {
        if (plaintext == null || plaintext.isEmpty()) return null

        val bytes = Utf8.encode(plaintext)
        return try {
            Aead.seal(dek, CryptoAad.field(entryId, fieldName), bytes)
        } finally {
            bytes.fill(0)   // 中间缓冲区用完立刻清零
        }
    }

    /**
     * 解密。
     * @throws TamperedDataException 密钥不匹配 / AAD 不匹配 / 密文被篡改
     */
    override fun decrypt(entryId: String, fieldName: String, blob: ByteArray?): CharArray? {
        if (blob == null || blob.isEmpty()) return null

        val bytes = Aead.open(dek, CryptoAad.field(entryId, fieldName), blob)
        return try {
            Utf8.decode(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * 字符串便捷入口。**仅用于非敏感字段**（标题、网址、备注、标签）。
     *
     * 它会构造 String，字符驻留堆中无法主动擦除。
     * 密码、TOTP 种子、安全问题答案等一律走 [encrypt]/[decrypt] 的 CharArray 版本。
     */
    override fun encryptString(entryId: String, fieldName: String, value: String?): ByteArray? {
        if (value == null) return null
        val chars = value.toCharArray()
        return try {
            encrypt(entryId, fieldName, chars)
        } finally {
            chars.fill('\u0000')
        }
    }

    /** 字符串便捷入口，仅用于非敏感字段。见 [encryptString] 的警告。 */
    override fun decryptString(entryId: String, fieldName: String, blob: ByteArray?): String? {
        val chars = decrypt(entryId, fieldName, blob) ?: return null
        return try {
            String(chars)
        } finally {
            chars.fill('\u0000')
        }
    }
}
