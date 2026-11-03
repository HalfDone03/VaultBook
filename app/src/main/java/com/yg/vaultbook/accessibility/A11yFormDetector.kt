package com.yg.vaultbook.accessibility

/**
 * 识别结果。密码是**明文**（无障碍拿到的就是明文），
 * 调用方用完必须把 [password] 置 null，绝不打日志。
 */
data class A11yLoginForm(
    val username: String? = null,
    val password: String? = null,
    val submitLabels: List<String> = emptyList(),
    val siteCandidates: List<String> = emptyList(),
    val pageTitle: String? = null,
    val passwordFieldCount: Int = 0,
    val usernameFieldCount: Int = 0
) {

    val hasPassword: Boolean get() = passwordFieldCount > 0

    val hasUsername: Boolean get() = !username.isNullOrBlank()

    /** 登录页的最低条件：至少有一个密码框。 */
    val looksLikeLogin: Boolean get() = hasPassword

    /**
     * 像注册页 / 改密页吗（页面上有两个及以上密码框）。
     *
     * 这类页面的密码是"新设的"，用户很可能还要过验证码、勾协议，
     * 中途失败就作废了。把它当登录凭据存起来，下次自动填充会填一个从没生效过的密码，
     * 反而让用户以为密码管理器坏了。所以上层**跳过**这类页面（见 docs/08）。
     *
     * 代价写在文档里：在某个 App 里注册时，第一遍不会自动记下来。
     * 这是有意的取舍 —— 宁可漏记（用户能手填），不要记错（会覆盖掉能用的那条）。
     */
    val looksLikeRegistration: Boolean get() = passwordFieldCount >= 2
}

/**
 * 从扁平化的节点快照里认出登录表单。
 *
 * ── 为什么不用 viewId 直接匹配 ────────────────────────────────
 * WebView 里的输入框，`viewIdResourceName` 一律为空（网页里的 id 不会透出来）；
 * Flutter / React Native 自绘的输入框更彻底，类名甚至可能是 `android.view.View`。
 * 所以这里只依赖三样东西，按可靠性从高到低：
 *
 * 1. `isPassword`（系统给的标志位）—— 这是唯一真正可信的信号；
 * 2. 可编辑性 + 文案关键词（hint / contentDescription / 类名）；
 * 3. **树序**：DOM 里账号框总在密码框前面，取"密码框之前最后一个可编辑框"
 *    是覆盖自绘页面的唯一办法。
 *
 * ── 一个刻意的排除 ────────────────────────────────────────────
 * 搜索框也是可编辑框，也会出现在很多页面上。不排除它的话，在淘宝首页点搜索框、
 * 输入关键词、点"搜索"，会被当成一次登录 —— 然后库里多一条
 * 「账号=关键词，密码=（空）」的垃圾记录。所以 [SEARCH_HINTS] 里的词优先级最高，
 * 命中即判为"不是账号框"。
 */
object A11yFormDetector {

    private const val MAX_SITE_CANDIDATES = 12

    /**
     * 浏览器界面自己的字眼，永远不会是"这条记录叫什么"。
     *
     * 加这批词不是为了认站点，是为了**别把浏览器的工具栏当成站点名** ——
     * 一旦记成「夸克」「新建标签页」，用户下次在同一个浏览器里打开别的网站，
     * 自动填充就会拿着这条去匹配，等于把凭据往错误的站点上填。
     */
    private val CHROME_WORDS = setOf(
        "新建标签页", "新标签页", "无痕模式", "无痕浏览", "隐私模式",
        "首页", "书签", "历史记录", "下载", "收藏", "窗口", "菜单", "更多",
        "分享", "刷新", "返回", "前进", "关闭", "搜索或输入网址", "搜索或输入网站",
        "输入网址", "搜索", "取消", "确定", "完成", "设置",
        "new tab", "incognito", "bookmarks", "history", "downloads", "settings"
    )

    /**
     * 浏览器里"网页正文"的最小深度。
     *
     * 浏览器 UI 是 `DecorView → 若干层 → 工具栏/标签栏`（深度一般 ≤ 3），
     * 网页内容要再往下穿过 WebView 容器（深度 ≥ 4）。这个数字只是"先往深处找"
     * 的起点，找不到仍会退回全树，所以调错不会漏标题，只会多退一步。
     */
    private const val BROWSER_CONTENT_MIN_DEPTH = 4

    /** 密码框关键词。中文优先，因为国内 App 的 hint 基本都是中文。 */
    private val PASSWORD_HINTS = listOf(
        "password", "passwd", "pwd", "passcode", "secret",
        "密码", "口令", "登录密码", "交易密码"
    )

    /** 账号框关键词。**刻意不含单独的 `name`** —— 那多半是真实姓名或收货人。 */
    private val USERNAME_HINTS = listOf(
        "username", "user", "account", "login", "email", "phone", "mobile", "tel",
        "账号", "帐号", "账户", "用户名", "登录名", "手机号", "手机", "邮箱", "邮件", "身份证"
    )

    /** 搜索类关键词，命中即排除。 */
    private val SEARCH_HINTS = listOf(
        "search", "query", "keyword", "filter", "搜索", "查询", "搜一搜", "筛选"
    )

    /** 提交按钮文案。用于判定"用户按了登录"，也用于从站点候选里剔除。 */
    private val SUBMIT_LABELS = listOf(
        "登录", "登陆", "立即登录", "确认登录", "提交", "下一步", "继续",
        "sign in", "signin", "log in", "login", "submit", "continue", "next"
    )

    fun detect(
        nodes: List<NodeSnapshot>,
        pageChrome: Set<String> = emptySet(),
        preferContentTitle: Boolean = false
    ): A11yLoginForm {
        val visible = nodes.filter { it.visible }
        if (visible.isEmpty()) return A11yLoginForm()

        val passwordIdx = visible.indices.filter { isPasswordNode(visible[it]) }
        val usernameIdx = pickUsernameIndex(visible, passwordIdx)

        val passwordFieldCount = passwordIdx.size
        // 注册页/改密页：密码框可能有两个（"密码" + "确认密码"）。
        // 只取第一个密码框的值会拿到"设的新密码"，对登录没用，但仍如实返回，
        // 由调用方用 looksLikeRegistration 决定是否丢弃。
        val password = passwordIdx.firstOrNull()
            ?.let { visible[it].text }
            ?.takeIf { it.isNotEmpty() }

        val username = usernameIdx
            ?.let { visible[it].text }
            ?.takeIf { it.isNotBlank() }

        val submitLabels = visible
            .asSequence()
            .filter { it.clickable && it.hasText }
            .mapNotNull { it.text }
            .filter { isSubmitLabel(it) }
            .distinct()
            .take(4)
            .toList()

        return A11yLoginForm(
            username = username,
            password = password,
            submitLabels = submitLabels,
            siteCandidates = collectSiteCandidates(visible, passwordIdx, usernameIdx),
            pageTitle = pickPageTitle(visible, pageChrome, preferContentTitle),
            passwordFieldCount = passwordFieldCount,
            usernameFieldCount = if (usernameIdx != null) 1 else 0
        )
    }

    /**
     * 这段文本是不是"提交"类按钮的文案。
     *
     * 用 `contains` 而不是精确相等，因为按钮文案千变万化（"立即登录"、"登录 / 注册"）。
     * 但**调用方必须自己在可点击的控件上才用它** —— 页面标题"登录 - 淘宝网"也含
     * 「登录」，把它当按钮会让标题被丢掉。所以本函数只回答"这段文案像不像提交动作"，
     * 不回答"这个控件是不是按钮"。
     */
    fun isSubmitLabel(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val t = text.trim().lowercase()
        if (t.length > 16) return false
        return SUBMIT_LABELS.any { t == it || t.contains(it) }
    }

    // ── 内部 ─────────────────────────────────────────────────────

    private fun isPasswordNode(node: NodeSnapshot): Boolean {
        if (!node.editable && !node.password) return false
        if (node.password) return true
        val hay = node.haystack()
        if (SEARCH_HINTS.any { hay.contains(it) }) return false
        return PASSWORD_HINTS.any { hay.contains(it) }
    }

    private fun isUsernameNode(node: NodeSnapshot): Boolean {
        if (!node.editable) return false
        if (isPasswordNode(node)) return false
        val hay = node.haystack()
        if (SEARCH_HINTS.any { hay.contains(it) }) return false
        return USERNAME_HINTS.any { hay.contains(it) }
    }

    /**
     * 找账号框。两级策略：
     * 1. 文案里明确写着账号/手机/邮箱的，取第一个；
     * 2. 没有明确文案（自绘页面常态）：取密码框**之前**最后一个可编辑框。
     *
     * 不用"离密码框最近"这种几何判断 —— 无障碍节点树没有坐标，
     * 只有先序顺序，而先序顺序在网页表单里恰好等于视觉上的从上到下。
     */
    private fun pickUsernameIndex(nodes: List<NodeSnapshot>, passwordIdx: List<Int>): Int? {
        if (passwordIdx.isEmpty()) return null

        val named = nodes.indices.firstOrNull { isUsernameNode(nodes[it]) }
        if (named != null) return named

        val firstPassword = passwordIdx.first()
        return nodes.indices.lastOrNull { it < firstPassword && isPlainEditable(nodes[it]) }
    }

    private fun isPlainEditable(node: NodeSnapshot): Boolean =
        node.editable && !isPasswordNode(node) && !SEARCH_HINTS.any { node.haystack().contains(it) }

    /**
     * 收集"可能是当前站点"的文本候选，已按可信度排序。
     *
     * 排序规则：id/描述像地址栏的排前面，其余有文本的排后面。
     * 输入框本身（账号框、密码框、其它可编辑框）的文本**一律不收** ——
     * 那里面是用户输入的内容，`alice@example.com` 这种会被误当成域名。
     */
    private fun collectSiteCandidates(
        nodes: List<NodeSnapshot>,
        passwordIdx: List<Int>,
        usernameIdx: Int?
    ): List<String> {
        val excluded = (passwordIdx + listOfNotNull(usernameIdx)).toSet()

        val scored = mutableListOf<Pair<Int, String>>()
        nodes.forEachIndexed { i, node ->
            if (i in excluded) return@forEachIndexed
            if (node.editable) return@forEachIndexed
            val text = node.text?.trim()
            if (text.isNullOrEmpty() || text.length > 200) return@forEachIndexed
            // 提交按钮的文案不可能是域名。注意限定 clickable ——
            // 页面标题里的「登录」二字不该被当成按钮排掉。
            if (node.clickable && isSubmitLabel(text)) return@forEachIndexed

            val rank = when {
                BrowserHosts.looksLikeAddressBar(node.viewId, node.description) -> 0
                else -> 1
            }
            scored.add(rank to text)
        }

        return scored
            .sortedBy { it.first }
            .map { it.second }
            .distinct()
            .take(MAX_SITE_CANDIDATES)
    }

    /**
     * 页面标题：取"最浅的、有文本的、不可编辑的、不是按钮的"节点。
     *
     * 浏览器里这通常是标签页标题或网页 `<title>`，两者都足够当"这条记录叫什么"。
     * 拿不到就返回 null —— 上层会把标题退化成站点名，不影响功能。
     *
     * ── 浏览器里那个「最浅」是错的 ───────────────────────────────
     * 浏览器自己的界面（工具栏、标签栏、地址栏占位字）比网页内容浅得多，
     * 于是取最浅节点必然拿到「夸克」这种 App 名：用户在夸克里登录 QQ 邮箱，
     * 记下来的一条叫「夸克」，等于把浏览器当成了站点。
     *
     * 所以浏览器里改成两条规则：
     * 1. [pageChrome] 里列出的宿主文案（浏览器名）**永远不参与**；
     * 2. 先在深于 [BROWSER_CONTENT_MIN_DEPTH] 的节点里找 —— 网页正文总在 WebView 里，
     *    比浏览器的工具栏深；实在找不到才退回全树。
     *
     * @param pageChrome 宿主应用自己的界面文案，不许当标题
     * @param preferContentTitle 是否按"先取网页正文"的规则找（浏览器里为 true）
     */
    private fun pickPageTitle(
        nodes: List<NodeSnapshot>,
        pageChrome: Set<String>,
        preferContentTitle: Boolean
    ): String? {
        val usable = nodes.asSequence()
            .filter { !it.editable && it.hasText }
            .filter { node -> !(node.clickable && isSubmitLabel(node.text)) }
            .filter { (it.text?.length ?: 0) in 2..80 }
            .filter { node ->
                val t = node.text!!.trim()
                val lower = t.lowercase()
                pageChrome.none { it.isNotBlank() && t.equals(it, ignoreCase = true) } &&
                    lower !in CHROME_WORDS
            }

        if (preferContentTitle) {
            // 固定阈值在不同浏览器上都会踩空：小米浏览器把整棵树压得比 Chrome 浅，
            // 门槛卡 4 就一个候选都不剩，退回去又拿到工具栏。
            // 改成"看这棵树里浏览器自己的界面停在第几层"，取比它更深的那批。
            val chromeDepth = nodes.asSequence()
                .filter { (it.text?.length ?: 0) in 1..80 }
                .filter { node ->
                    val t = node.text!!.trim()
                    pageChrome.any { it.isNotBlank() && t.equals(it, ignoreCase = true) } ||
                        t.lowercase() in CHROME_WORDS
                }
                .maxOfOrNull { it.depth }
                ?: (BROWSER_CONTENT_MIN_DEPTH - 1)

            val below = usable.filter { it.depth > chromeDepth }
            val pool = if (below.any()) below else usable
            pool.minByOrNull { it.depth }?.let { return tidyPageTitle(it.text) }
        }
        return usable.minByOrNull { it.depth }?.text?.trim()
    }

    /** 网页标题里"这只是一张登录页"的成分，剪掉才像站点名。 */
    private val TITLE_NOISE = setOf(
        "登录", "登陆", "登录页", "登录页面", "注册", "注册页",
        "sign in", "signin", "log in", "login", "sign up", "account login"
    )

    /** 标题分隔符：`QQ邮箱 - 登录`、`登录 | QQ邮箱`、`QQ邮箱·登录`。 */
    private val TITLE_SPLIT = Regex("\\s*[|｜]\\s*|\\s+[-—–·]\\s+")

    /**
     * 把网页标题收成站点名：`QQ邮箱 - 登录` → `QQ邮箱`，`登录 | QQ邮箱` → `QQ邮箱`。
     *
     * 只在浏览器里做（[preferContentTitle]）。规则刻意只剪"整段就是一个登录词"的情况，
     * 不做模糊截断 —— 标题里带「登录」的站点名是存在的（如「登录宝」）。
     */
    private fun tidyPageTitle(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val kept = text.split(TITLE_SPLIT)
            .map { it.trim() }
            .filter { it.length >= 2 && it.lowercase() !in TITLE_NOISE }
        return (kept.firstOrNull() ?: text).take(80)
    }
}
