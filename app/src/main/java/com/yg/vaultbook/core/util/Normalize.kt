package com.yg.vaultbook.core.util

/**
 * 字符串与域名的归一化。
 *
 * 这层存在的唯一目的：让"同一个站点"在数据库里只有一个 key。
 * 否则 `https://www.Taobao.com/login?x=1` 和 `taobao.com` 会被当成两个站点，
 * 自动填充就永远匹配不上用户手填的那条记录。
 *
 * ⚠️ 已知局限（有意为之，P2 再补）：
 * `hostFromUrl` 用"最后两段"近似 eTLD+1，只对 [SECOND_LEVEL_SUFFIXES] 里那 20 个后缀
 * 做了三段修正。表外的多段后缀会算错，例如 `me.example.github.io` 会得到
 * `github.io`（正确应是 `example.github.io`）—— 后果是两个不同的 GitHub Pages
 * 站点共用一条记录。正确做法是接入 Public Suffix List（约 200KB，压进 APK 可接受）。
 * 在那之前，宁可给出这个粗糙结果也不要"猜"：猜错会让两个不同站点共用一条记录。
 */
object Normalize {

    /** 常见二级后缀。命中时取最后三段，把 `co.uk` / `com.cn` 这类情况修正过来。 */
    private val SECOND_LEVEL_SUFFIXES = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk",
        "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn",
        "com.hk", "com.tw", "com.sg", "com.au", "co.jp", "co.kr",
        "com.br", "com.mx", "co.in", "com.tr", "com.ru"
    )

    /**
     * 从 URL 或裸域名提取用于匹配的主机名。
     *
     * @return 归一化后的主机（如 `taobao.com`），无法解析时返回 null
     */
    fun hostFromUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null

        var s = raw.trim().lowercase()

        // 去掉协议
        val schemeEnd = s.indexOf("://")
        if (schemeEnd >= 0) s = s.substring(schemeEnd + 3)

        // 去掉 userinfo（user:pass@host 里的前半段）
        val at = s.indexOf('@')
        if (at >= 0) s = s.substring(at + 1)

        // 去掉路径 / 查询 / 片段
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')

        // 去掉端口（IPv6 的 [::1]:8080 也能处理）
        val closeBracket = s.indexOf(']')
        if (s.startsWith("[") && closeBracket > 0) {
            s = s.substring(0, closeBracket + 1)
        } else {
            s = s.substringBefore(':')
        }

        if (s.isEmpty()) return null

        // IPv4 / IPv6 原样返回，不要试图取"最后两段"
        if (s.startsWith("[") || s.all { it.isDigit() || it == '.' }) return s

        s = s.trimEnd('.')

        // 去掉 www 前缀（保留其它子域，因为 new.taobao.com 和 taobao.com 语义上同站）
        if (s.startsWith("www.")) s = s.removePrefix("www.")

        if (!s.contains('.')) return s

        return eTldPlusOne(s)
    }

    private fun eTldPlusOne(host: String): String {
        val parts = host.split('.').filter { it.isNotEmpty() }
        if (parts.size <= 2) return parts.joinToString(".")

        val lastTwo = parts.takeLast(2).joinToString(".")
        val lastThree = parts.takeLast(3).joinToString(".")
        // 命中已知二级后缀（如 co.uk）时多保留一段
        return if (SECOND_LEVEL_SUFFIXES.contains(lastTwo)) lastThree else lastTwo
    }

    /**
     * 搜索用的归一化：小写 + 去首尾空白 + 全角转半角 + 连续空白折叠。
     *
     * 不做拼音、不做模糊匹配 —— 那些必须等真正有用户抱怨再说，
     * 否则"输错一个字母也搜出结果"反而让人不敢用。
     */
    fun forSearch(raw: String): String {
        val sb = StringBuilder(raw.length)
        var lastWasSpace = false
        for (ch in raw.trim()) {
            val c = toHalfWidth(ch)
            if (c.isWhitespace()) {
                if (!lastWasSpace) sb.append(' ')
                lastWasSpace = true
            } else {
                sb.append(c.lowercaseChar())
                lastWasSpace = false
            }
        }
        return sb.toString()
    }

    /** 全角 ASCII（U+FF01..U+FF5E）与全角空格（U+3000）转半角，其它字符原样返回。 */
    fun toHalfWidth(ch: Char): Char = when (ch) {
        '\u3000' -> ' '
        in '\uFF01'..'\uFF5E' -> (ch.code - 0xFEE0).toChar()
        else -> ch
    }
}
