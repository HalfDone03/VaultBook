package com.yg.vaultbook.core.util

/**
 * 「这条是不是已经存过了」的判定签名。
 *
 * 为什么要单独一个纯函数：批量粘贴去重、CSV 导入跳过、以后可能的合并，
 * 三处都得用同一套判定，各写一遍必然对不上。这里只放规则，不放 IO，
 * 纯 JVM 单测能直接跑。
 *
 * 规则：
 * - 标题、账号各自 `trim` 后转小写，全角半角自然由 [Normalize.toHalfWidth] 收口
 *   （「ＱＱ｜密码」和「qq|密码」算同一个）；
 * - 中间用 `\u0001` 分隔，不用 `|` —— 标题里真的会写竖线（「QQ|微信」），
 *   用竖线拼会撞车；
 * - 账号为 null 与空串视为同一种（没填账号），否则「存平台+密码」那条
 *   永远对不上第二次。
 *
 * 只看标题加账号、**不看密码**：同平台改了个密码再存一条，是用户真想留两条
 * 历史，还是手滑重复？产品上选前者 —— 密码不同不算重复，免得把用户的
 * 新密码当垃圾跳掉。
 */
object EntrySignature {

    private const val SEP = '\u0001'

    fun of(title: String?, username: String?): String {
        return normalize(title) + SEP + normalize(username)
    }

    private fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        // 直接复用搜索用的归一化：去首尾空白、全角转半角、折叠连续空格、转小写。
        // 与搜索保持同一把尺子，用户看起来"一样"的两条，在这里也一定是同一个签名。
        return Normalize.forSearch(raw)
    }
}
