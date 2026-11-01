package com.yg.vaultbook.core.crypto

/**
 * 明文直通实现：字节直接就是 UTF-8，不做任何加密。
 *
 * ── 为什么会有这个类 ─────────────────────────────────────────
 * 这个 App 的使用场景已经明确：**自己用、只在自己手机上、打开就要看到**。
 * 之前那套"主密码 → KDF → DEK → SQLCipher"是为"零知识保险库"设计的，
 * 代价是每次启动都要输一次密码，忘密码等于数据全没。对"日常速查"来说
 * 这笔账不划算 —— 用户原本的做法就是把密码抄在手机记事本里。
 *
 * 所以这里换成零知识：与记事本等价，但检索、分类、自动填充、一键复制
 * 这些地方比记事本顺手得多。
 *
 * ── 边界要清楚 ───────────────────────────────────────────────
 * 存进数据库的字段就是明文 UTF-8 字节。任何能读到 `/data/data/
 * com.yg.vaultbook/databases/vault.db` 的人（root、备份提取、调试版
 * 被 adb backup）都能直接看到账号密码。这与"手机记事本"的风险完全同级，
 * 没有更高，也没有更低。
 *
 * 列名仍叫 `xxx_ct`（历史原因，表结构不动），内容已不再是密文。
 *
 * 空值与空串统一返回 null：数据库里"没有这个字段"和"字段是空字符串"
 * 不该有两种表示，否则 UI 上会出现"有值的空行"。
 */
class PlainFieldCrypto : FieldCrypto {

    override fun encrypt(entryId: String, fieldName: String, plaintext: CharArray?): ByteArray? {
        if (plaintext == null || plaintext.isEmpty()) return null
        return Utf8.encode(plaintext)
    }

    override fun decrypt(entryId: String, fieldName: String, blob: ByteArray?): CharArray? {
        if (blob == null || blob.isEmpty()) return null
        return Utf8.decode(blob)
    }
}
