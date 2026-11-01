package com.yg.vaultbook.core.crypto

/**
 * 字段级加解密的能力接口。
 *
 * 为什么需要这个接口（而不是到处直接用 [FieldCipher]）：
 *
 * 系统里有**两条**加密通道，密钥来源完全不同：
 *
 * | 实现 | 密钥 | 锁定时可用 | 用途 |
 * |---|---|---|---|
 * | [FieldCipher] | 主密码派生的 DEK | ❌ 需要主密码 | 正式条目（密码、备注…） |
 * | [com.yg.vaultbook.core.keystore.KeystoreFieldCipher] | AndroidKeyStore 设备密钥 | ✅ 不需要主密码 | 待确认的捕获 |
 *
 * 第二条不是"图省事"，而是**功能成立的前提**：
 * 用户在浏览器里登录站点的那一刻，本 App 大概率处于锁定状态（自动锁定 30 秒）。
 * 如果捕获通道要求先解锁，那"自动抓取登录密码"这个核心诉求就永远用不上。
 *
 * 代价必须写清楚：Keystore 通道的密文在设备解锁状态下可被本 App 读取，不需要主密码。
 * 因此它**只用于 `pending_captures`** —— 寿命最长 7 天、且只在"待确认"状态；
 * 一旦用户点保存，数据立刻用 DEK 重新加密写入 `entries`，旧的 Keystore 密文删除。
 *
 * AAD 约定与 [FieldCipher] 完全一致（`entryId|fieldName`），所以两条通道的密文
 * 不能互换 —— 这正好防止"把待确认密文塞进正式条目"这类搬运。
 */
interface FieldCrypto {

    /**
     * 加密一段字符。
     *
     * @return `null` 表示"该字段不存在/为空"，调用方无需区分空串与 null
     */
    fun encrypt(entryId: String, fieldName: String, plaintext: CharArray?): ByteArray?

    /**
     * 解密。
     *
     * @throws TamperedDataException 密文被改动、AAD 不匹配，或密钥已失效
     */
    fun decrypt(entryId: String, fieldName: String, blob: ByteArray?): CharArray?

    /**
     * 字符串便捷入口，**仅供非敏感字段**（标题、账号、网址、备注、分类名、自定义字段标签）。
     *
     * 它会构造 `String`，字符驻留堆中无法主动擦除。密码类字段一律走
     * [encrypt]/[decrypt] 的 CharArray 版本。
     *
     * 默认实现放在接口上，是为了让 [PlainFieldCrypto] 这类实现不必重复写一遍
     * 转换逻辑，也保证两种实现的行为完全一致。
     */
    fun encryptString(entryId: String, fieldName: String, value: String?): ByteArray? {
        if (value == null) return null
        val chars = value.toCharArray()
        return try {
            encrypt(entryId, fieldName, chars)
        } finally {
            chars.fill('\u0000')
        }
    }

    /** 字符串便捷入口，仅用于非敏感字段。见 [encryptString] 的说明。 */
    fun decryptString(entryId: String, fieldName: String, blob: ByteArray?): String? {
        val chars = decrypt(entryId, fieldName, blob) ?: return null
        return try {
            String(chars)
        } finally {
            chars.fill('\u0000')
        }
    }
}
