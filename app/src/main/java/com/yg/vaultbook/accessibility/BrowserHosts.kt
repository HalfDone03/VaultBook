package com.yg.vaultbook.accessibility

import com.yg.vaultbook.core.util.Normalize

/**
 * 从浏览器界面里"认出当前是哪个网站"。
 *
 * ── 这是整个无障碍通道最不可靠的一环 ──────────────────────────
 * `AssistStructure` 能给出 `webDomain`（系统直接告诉你是哪个域名），无障碍**没有**这个字段。
 * 唯一线索是地址栏那个节点上的文本。而地址栏文本可能是：
 *
 * | 情况 | 地址栏文本 | 能不能用 |
 * |---|---|---|
 * | 已加载完、失焦 | `taobao.com` | 能用 |
 * | 正在聚焦编辑 | `https://taobao.com/login?x=1` | 能用（要剥路径） |
 * | 新建标签页 | `搜索或输入网址` | 用不了 → 返回 null |
 * | 搜索结果页 | `淘宝网 - 淘！我喜欢` | **用不了且绝不能猜** |
 * | 中文域名 | `例子.中国` | 用不了（TLD 是中文，直接放弃） |
 *
 * ── 铁律：宁可空着，不要猜 ────────────────────────────────────
 * host 是加密库里唯一的"站点键"。猜错的后果是把 A 站的账号密码挂到 B 站名下，
 * 下次在 B 站自动填充就会把 A 的凭据填进去 —— 这是能把密码发给钓鱼站的事故。
 * 所以这里只做**格式强校验**：不像域名的文本一律丢弃，拿不到就返回 null，
 * 由上层降级为「只按包名匹配」或「让用户手填站点名」。
 *
 * ⚠️ 另一个已知局限：只在 [PACKAGES] 白名单里的浏览器才尝试解析地址栏。
 * 表外浏览器（系统定制 ROM 自带的、小众浏览器）走"包名匹配"这条路，
 * 功能仍然可用，只是同一个浏览器里的不同网站会被当成同一个来源。
 * 加白名单很容易，但每加一个都要真机确认它的地址栏节点长什么样，
 * 所以这里只放经过确认的一批。
 */
object BrowserHosts {

    /**
     * 浏览器包名白名单。
     *
     * 判断标准不是"是不是浏览器"，而是「地址栏节点的文本是不是站点的裸域名」。
     * 下面这些是 Android 上常见、且地址栏行为为"失焦后显示域名"的一批。
     */
    val PACKAGES: Set<String> = setOf(
        // Chrome 系
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "org.chromium.chrome",
        // Firefox 系
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        // Edge / Brave / Opera / DuckDuckGo
        "com.microsoft.emmx",
        "com.brave.browser",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.duckduckgo.mobile.android",
        // 国内常见
        "com.UCMobile",
        "com.tencent.mtt",
        "com.quark.browser",
        "com.baidu.searchbox",
        "com.baidu.browser",
        "com.sec.android.app.sbrowser",
        "com.android.browser",
        "com.miui.browser",
        "com.heytap.browser",
        "com.vivo.browser",
        "com.huawei.browser",
        "com.zui.browser",
        "com.transsion.browser"
    )

    /** 地址栏节点的 id 名特征（`viewIdResourceName` 的最后一段，小写）。 */
    private val ADDRESS_BAR_ID_HINTS = listOf("url", "omnibox", "address", "location", "search_box", "addr")

    fun isBrowser(pkg: String?): Boolean = pkg != null && pkg in PACKAGES

    /**
     * 这个节点的 id / 描述看起来像地址栏吗。
     *
     * 只用来**排序候选**，不用来放行或拒绝 —— 有些浏览器地址栏没有 id，
     * 完全靠文本格式那一步兜底。
     */
    fun looksLikeAddressBar(viewId: String?, description: String?): Boolean {
        val hay = listOfNotNull(viewId, description).joinToString(" ").lowercase()
        if (hay.isEmpty()) return false
        return ADDRESS_BAR_ID_HINTS.any { hay.contains(it) }
    }

    /**
     * 从一串候选文本里取第一个能确认的站点。
     *
     * 调用方应当把"更像地址栏"的候选排在前面（参见 [looksLikeAddressBar]）。
     */
    fun hostFromCandidates(candidates: List<String?>): String? {
        for (raw in candidates) {
            val host = hostFromText(raw)
            if (host != null) return host
        }
        return null
    }

    /**
     * 把一段文本当成可能的站点标识来解析。
     *
     * @return 归一化后的 host（如 `taobao.com`），判断不了就返回 null
     */
    fun hostFromText(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        // 含 @ 的一律拒绝。真实 URL 里出现 userinfo 极少（地址栏不会这么显示），
        // 而"输入框里填了邮箱"极常见 —— 不去掉 userinfo 的话
        // `alice@example.com` 会被解析成 `example.com`，把整站认错。
        if (raw.contains('@')) return null
        val host = extractHostPart(raw)
        if (!looksLikeHost(host)) return null
        // 走到这里 host 已经过格式强校验，再交给 Normalize 做 eTLD+1 归一。
        // 顺序不能反：Normalize.hostFromUrl 对没有点号的任意文本会原样返回
        // （见 Normalize.kt 第 66 行），"淘宝" 这种词会被当成合法 host。
        return Normalize.hostFromUrl(host)
    }

    /** 剥掉协议、userinfo、路径、查询、片段、端口。IPv6 的方括号保留。 */
    private fun extractHostPart(raw: String): String {
        var s = raw.trim()
        val schemeEnd = s.indexOf("://")
        if (schemeEnd >= 0) s = s.substring(schemeEnd + 3)
        val at = s.indexOf('@')
        if (at >= 0) s = s.substring(at + 1)
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        val closeBracket = s.indexOf(']')
        if (s.startsWith("[") && closeBracket > 0) return s.substring(0, closeBracket + 1)
        return s.substringBefore(':').trimEnd('.')
    }

    private val LABEL = "([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)"
    private val HOST_PATTERN = Regex("^$LABEL(\\.$LABEL)+$", RegexOption.IGNORE_CASE)
    private val TLD_PATTERN = Regex("^[a-z]{2,24}$", RegexOption.IGNORE_CASE)
    private val IPV4_PATTERN = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /**
     * 严格判断一段文本是不是"像样的主机名"。
     *
     * 刻意保守：
     * - 必须含至少一个点（所以 `localhost`、`淘宝` 一律不算）
     * - 顶级标签必须纯字母 2–24 位（排除 `1.5`、`v1.2.3` 这类版本号，也排除中文域名）
     * - 每段必须以字母数字开头结尾，内部允许连字符
     */
    fun looksLikeHost(host: String): Boolean {
        if (host.isEmpty()) return false
        if (host.startsWith("[") && host.endsWith("]")) {
            // IPv6 字面量，形如 [::1]
            val inner = host.substring(1, host.length - 1)
            return inner.contains(':')
        }
        if (IPV4_PATTERN.matches(host)) return true
        if (!HOST_PATTERN.matches(host)) return false
        val tld = host.substringAfterLast('.')
        return TLD_PATTERN.matches(tld)
    }
}
