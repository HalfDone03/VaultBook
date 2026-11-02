package com.yg.vaultbook.core.util

/**
 * 把一段"从别处粘过来的文本"拆成 账号 / 密码 / 网址 / 标题。
 *
 * ── 为什么需要它 ─────────────────────────────────────────────
 * 用户实际的记录方式是把东西抄在记事本里，格式五花八门：
 * ```
 * 账号：admin 密码：abc123
 * 淘宝  13800138000  p@ssw0rd
 * www.taobao.com
 * admin
 * abc123
 * ```
 * 要求用户先手工拆成四个框再输入，等于把"方便"这件事做反了。
 * 所以让 App 自己认。
 *
 * ── 设计取舍 ─────────────────────────────────────────────────
 * 纯函数、零 Android 依赖 —— 这样才能在 JVM 上把各种脏格式都测一遍。
 *
 * 识别分两步，**先精确、后猜测**：
 * 1. 找 `key: value` 形式（账号/密码/网址/标题的近义词表），命中就是命中，
 *    绝不改动用户的原始值；
 * 2. 剩下没说清的部分再按位置猜（第一行是站点名还是账号？见
 *    [looksLikeSiteName]）。
 *
 * 为什么坚持"不改原始值"：密码里出现全角字符、空格、中文标点都是合法的。
 * 一旦为了识别方便去归一化文本，写进库的密码就和真实密码不一样了 ——
 * 而这种错误用户当场看不出来，等他去登录才发现。所以归一化只用于
 * *定位*（比较 key 名），取值一律从原文切。
 *
 * 猜错也没关系：结果只是"预填"，用户在编辑页还能改。
 */
object CredentialParser {

    /** 解析结果。所有字段都可能为 null；[hasCredential] 才代表"值得预填"。 */
    data class Result(
        val username: String? = null,
        val password: String? = null,
        val url: String? = null,
        val title: String? = null,
    ) {
        /** 一个字段都没认出来。 */
        val isEmpty: Boolean
            get() = username == null && password == null && url == null && title == null

        /**
         * 至少认出了账号或密码。
         *
         * 只认出网址不算数 —— 那多半是用户复制了一个链接，
         * 弹"检测到账号密码"会显得很蠢。
         */
        val hasCredential: Boolean
            get() = !username.isNullOrBlank() || !password.isNullOrBlank()
    }

    // 长的放前面：同一起点上，交替分支是"先匹配到的赢"，
    // 若 `user` 排在 `username` 前面，`username:` 会被切成 `user` + `name:`
    private val KV_REGEX = Regex(
        "(?i)" +
            "(?:(?<user>登录账号|登录名|用户名|用户|账号|帐号|账户|手机号|手机号码|手机|邮箱|邮件地址|邮箱地址|邮箱|username|userid|account|login|email|phone|mobile|user)" +
            "|(?<pass>password|passwd|passcode|pwd|secret|密码|口令)" +
            "|(?<url>website|domain|网址|网站|链接|地址|url|site|link|host)" +
            "|(?<title>title|name|app|标题|名称|站点|应用|平台|软件|app名))" +
            "\\s*[:：=＝]\\s*"
    )

    private val URL_REGEX = Regex("https?://[^\\s\"'<>）)】]+", RegexOption.IGNORE_CASE)

    private val HOST_REGEX = Regex(
        "(?i)\\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24}\\b"
    )

    /** 一行内多个值之间的分隔符。`----` 在抄密码时很常见，必须先于 `-` 处理。 */
    private val INLINE_SEPARATORS = Regex("[ \\t\\u3000]+|_{2,}|-{2,}|\\|+|,|，|;|；|/+")

    /** 连写行里紧贴在值前面的字段名：`云盘账户密码Zx8` 的「密码」。 */
    private val PASSWORD_LABEL_SUFFIX = Regex(
        "(密码|口令|password|passwd|pwd)$",
        RegexOption.IGNORE_CASE
    )

    /** 同上，但标明后面跟的是账号：`支付宝账号13800138000` 的「账号」。 */
    private val USER_LABEL_SUFFIX = Regex(
        "(账号|帐号|账户|用户名|手机号码|手机号|username|account)$",
        RegexOption.IGNORE_CASE
    )

    /** 一行里既没空格也没冒号的「名称 + 值」连写，拆开后的样子。 */
    private data class MixedLine(
        val head: String,
        val value: String,
        val isPassword: Boolean
    )

    /**
     * 主入口。`raw` 为空或认不出任何东西时返回 null。
     */
    fun parse(raw: String?): Result? {
        if (raw.isNullOrBlank()) return null

        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        var username: String? = null
        var password: String? = null
        var url: String? = null
        var title: String? = null

        // ── 第一步：key: value ────────────────────────────────────
        // 记录所有被 kv 吃掉的行，后面不能拿它们再当"无名值"
        val consumedLines = HashSet<Int>()
        var searchFrom = 0
        while (true) {
            val match = KV_REGEX.find(text, searchFrom) ?: break
            val valueStart = match.range.last + 1
            // 值只取到本行末尾：跨行取值会把下一条无关内容吞进来
            val lineEnd = text.indexOf('\n', valueStart).let { if (it < 0) text.length else it }
            // 同一行里可能还有下一个 key（`账号：admin 密码：abc123`）。
            // 此时值必须截止到它的起点，否则 username 会变成 `admin 密码：abc123`。
            val next = KV_REGEX.find(text, valueStart)
            val valueEnd = if (next != null && next.range.first < lineEnd) next.range.first else lineEnd
            val value = text.substring(valueStart, valueEnd).trim().trim(',', '，', ';', '；', '|')
            val lineIndex = text.take(match.range.first).count { it == '\n' }

            when {
                match.groups["user"] != null && username == null -> {
                    username = value.ifBlank { null }
                }
                match.groups["pass"] != null && password == null -> {
                    password = value.ifBlank { null }
                }
                match.groups["url"] != null && url == null -> {
                    url = cleanUrl(value) ?: value.ifBlank { null }
                }
                match.groups["title"] != null && title == null -> {
                    title = value.ifBlank { null }
                }
            }
            consumedLines += lineIndex
            searchFrom = if (valueEnd > valueStart) valueEnd else valueStart
        }

        // ── 第二步：从整段里找网址（哪怕没有 url 这个 key）──────────
        if (url == null) {
            URL_REGEX.find(text)?.let { url = cleanUrl(it.value) }
        }
        if (url == null) {
            // 裸域名。要排除 kv 已被用掉的用户名/标题，避免把 `admin.com` 当站点
            HOST_REGEX.findAll(text)
                .map { it.value }
                .firstOrNull { it != username && it != password && !it.endsWith(".apk") }
                ?.let { url = cleanUrl(it) }
        }

        // ── 第三步：剩下的行按位置猜 ─────────────────────────────
        val leftovers = text.split('\n')
            .mapIndexed { index, line -> index to line.trim() }
            .filter { (index, line) -> line.isNotEmpty() && index !in consumedLines }
            .map { it.second }
            // 已经当作网址的那一行不再参与。比较时两边都过一遍 cleanUrl：
            // 只粘了 `www.taobao.com` 时，url 是 `taobao.com`，
            // 不归一化就会被当成账号填进用户名框。
            .filter { line -> url == null || (cleanUrl(line) ?: line) != url }

        if (username == null || password == null) {
            // 先试连写：只剩一行、既没空格也没冒号时，`云盘账户密码Zx8Kq2Lm7V`
            // 这种形状按位置猜永远拆不开（整行都会被当成账号）。
            val mixed = leftovers.singleOrNull()?.let { splitMixed(it) }
            if (mixed != null) {
                if (mixed.isPassword) {
                    if (password == null) password = mixed.value
                } else {
                    if (username == null) username = mixed.value
                }
                if (title == null) title = mixed.head
            } else {
                val guess = guessFromLeftovers(leftovers, username, password)
                if (username == null) username = guess.first
                if (password == null) password = guess.second
                if (title == null) title = guess.third
            }
        }

        // 纯标点不算凭据。粘贴一段"、、；！！"之类的东西时不该弹"检测到账号密码"。
        // 注意这是"整体无意义"才丢弃：密码本身可以是纯符号（`!@#$%^&*`），
        // 只要账号那侧有字母数字，整组就保留。
        val meaningful = listOfNotNull(username, password)
            .any { value -> value.any { it.isLetterOrDigit() } }
        if (!meaningful) {
            username = null
            password = null
        }

        val result = Result(
            username = username?.trim()?.ifBlank { null },
            password = password, // 密码不做任何 trim / 归一化，见类注释
            url = url?.trim()?.ifBlank { null },
            title = title?.trim()?.ifBlank { null }
        )
        return if (result.isEmpty) null else result
    }

    /**
     * 一段文字里可能抄着好几组账号密码（记事本里一行一条地记）。
     * 按"最可信的形状先试"依次判断，认不出多条就退回单条：
     *
     * 0. 每行都是「名称紧贴值」的连写（`云盘账户密码Zx8Kq2Lm7V`）→ 一行一条；
     * 1. 每行都是多列（`淘宝 账号 密码` 一行一条）→ 一行一条；
     * 2. 空行分段，每段自成一册 → 一段一条；
     * 3. 关键字段重复出现（`平台：A 账号：x 密码：y 平台：B …`）→ 在每个重复的
     *    标题/账号键处切开，**只在前一条已经有密码之后才切**，否则
     *    `平台：A` `账号：x` `密码：y` 三行会被拆成三条；
     * 4. 都不成立 → 单条。
     *
     * 只在结果 ≥2 且每条都有凭据时才认为"识别到多条"。少拆比多拆好：
     * 拆错会让用户看到一堆半条记录，比只识别一条更劝退。
     */
    fun parseMany(raw: String?): List<Result> {
        val single = parse(raw)?.takeIf { it.hasCredential }?.let { listOf(it) } ?: emptyList()
        if (raw.isNullOrBlank()) return emptyList()

        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

        // 0) 一行一条的「连写」：`云盘账户密码Zx8Kq2Lm7V` 这种既没空格也没冒号，
        //    下面三条规则全都认不出（每行切不出 ≥2 段、没有空行、没有重复的 key），
        //    只会退化成一条错的 —— 标题丢了，账号变成整行，后面的行整段消失。
        if (lines.size >= 2 && lines.all { splitMixed(it) != null }) {
            val parsed = lines.mapNotNull { parse(it) }.filter { it.hasCredential }
            if (parsed.size >= 2) return parsed
        }

        // 1) 一行一条：每行都能切出 ≥2 段
        if (lines.size >= 2 && lines.all { splitInline(it).size >= 2 }) {
            val parsed = lines.mapNotNull { parse(it) }.filter { it.hasCredential }
            if (parsed.size >= 2) return parsed
        }

        // 2) 空行分段
        val blocks = text.split(Regex("\n\\s*\n+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (blocks.size >= 2) {
            val parsed = blocks.mapNotNull { parse(it) }.filter { it.hasCredential }
            if (parsed.size >= 2) return parsed
        }

        // 3) 重复 key：在"上一条已有密码之后又出现标题/账号键"的地方切
        val starts = recordStarts(text)
        if (starts.size >= 2) {
            val chunks = starts.indices.map { i ->
                val to = if (i + 1 < starts.size) starts[i + 1] else text.length
                text.substring(starts[i], to).trim()
            }
            val parsed = chunks.mapNotNull { parse(it) }.filter { it.hasCredential }
            if (parsed.size >= 2) return parsed
        }

        return single
    }

    /**
     * 每条记录的起始下标。第一条固定从 0 开始，其余落在"新记录的第一个键"上。
     *
     * 键全部挤在同一行时（`平台：A 密码：x 平台：B 密码：y`），回退到键本身的位置，
     * 否则两条会被并成一条 —— 那时候行首没有意义，字符位置才有。
     */
    private fun recordStarts(text: String): List<Int> {
        val starts = arrayListOf(0)
        var seenPassword = false
        KV_REGEX.findAll(text).forEach { match ->
            if (match.groups["pass"] != null) {
                seenPassword = true
                return@forEach
            }
            val isRecordHead = match.groups["title"] != null || match.groups["user"] != null
            if (!isRecordHead || !seenPassword) return@forEach
            val lineStart = text.lastIndexOf('\n', (match.range.first - 1).coerceAtLeast(0))
                .let { if (it < 0) 0 else it + 1 }
            val boundary = if (lineStart > starts.last()) lineStart else match.range.first
            if (boundary > starts.last()) starts.add(boundary)
            seenPassword = false
        }
        return starts
    }
    /**
     * 把「名称紧贴值」的一行拆成 (名称, 值, 值是不是密码)。
     *
     * 中文记事本里最常见的抄法就是这样：没有空格也没有冒号，
     * `云盘账户密码Zx8Kq2Lm7V`、`邮箱平台密码Zx8kq2lm7v.`。
     * 早先按位置猜的代码把它整行当成账号，后面几行直接丢掉 —— 用户的原话是
     * 「识别的太烂了」。
     *
     * 切点取**最后一处「中文→英文数字」的交界**，这样 `小米11密码abc123`
     * 也能切在 `…密码|abc123` 而不是 `小米|11密码abc123`。
     *
     * 认不出的情况一律返回 null，交回按位置猜的那条老路：
     * - 行里有空白（`淘宝 138 pwd` 是分隔符形状，不是连写）
     * - 行里有冒号/等号（那是 key: value，第一步已经处理过）
     * - 交界之后还有中文（说明没切成「名称 + 纯值」）
     * - 交界之后既没数字也没 `@`（`淘宝taobao.com` 那种是网址）
     */
    private fun splitMixed(line: String): MixedLine? {
        if (line.isEmpty()) return null
        if (line.any { it.isWhitespace() }) return null
        if (line.any { it in ":：=＝" }) return null

        var split = -1
        for (i in 1 until line.length) {
            if (line[i - 1].code >= 0x2E80 && line[i].code < 0x80) split = i
        }
        if (split <= 0) return null

        val head = line.substring(0, split).trim()
        val tail = line.substring(split).trim()
        if (head.isEmpty() || tail.isEmpty()) return null
        if (tail.any { it.code >= 0x2E80 }) return null
        if (tail.none { it.isDigit() } && !tail.contains('@')) return null

        val passwordLabel = PASSWORD_LABEL_SUFFIX.containsMatchIn(head)
        val userLabel = !passwordLabel && USER_LABEL_SUFFIX.containsMatchIn(head)
        val tailIsPassword = tail.any { it.isLetter() } && tail.any { it.isDigit() }

        val isPassword = when {
            // 名称里直接写了「密码」，那就别再猜了
            passwordLabel -> true
            // 写了「账号」而后面的值又不像密码（手机号、邮箱）→ 是账号
            userLabel && !tailIsPassword -> false
            else -> tailIsPassword
        }

        // 字段名只在不影响名称可读性时剥掉：
        // 「云盘账户密码」→「云盘账户」；「支付宝账号」→「支付宝」；
        // 但「云盘账户Zx8…」里的「账户」是这个平台名的组成部分，留着。
        val name = when {
            passwordLabel -> head.replace(PASSWORD_LABEL_SUFFIX, "").trim()
            userLabel && !tailIsPassword -> head.replace(USER_LABEL_SUFFIX, "").trim()
            else -> head
        }.ifEmpty { head }

        return MixedLine(name, tail, isPassword)
    }

    /**
     * 从没用掉的残行里推断 (账号, 密码, 标题)。
     *
     * 三种典型形状：
     * - 只剩一行 `admin 123456`  → 按分隔符切成两个
     * - 剩两行 → 第一行账号、第二行密码
     * - 剩三行 → 若第一行像站点名（"淘宝"而不是"admin"），它是标题
     */
    private fun guessFromLeftovers(
        lines: List<String>,
        knownUser: String?,
        knownPass: String?
    ): Triple<String?, String?, String?> {
        if (lines.isEmpty()) return Triple(null, null, null)

        if (lines.size == 1) {
            val parts = splitInline(lines[0])
            return when {
                parts.size >= 3 -> Triple(parts[1], parts[2], parts[0])
                parts.size == 2 -> Triple(parts[0], parts[1], null)
                else -> Triple(if (knownUser == null) lines[0].ifBlank { null } else null, null, null)
            }
        }

        // 两行及以上
        val first = lines[0]
        val startIndex = if (lines.size >= 3 && looksLikeSiteName(first)) 1 else 0
        val title = if (startIndex == 1) first else null

        val userIdx = startIndex
        val passIdx = startIndex + 1
        val user = lines.getOrNull(userIdx)?.ifBlank { null }
        val pass = lines.getOrNull(passIdx)?.ifBlank { null }

        // 如果认出来的"账号"其实是已知账号的重复，跳过它
        val finalUser = if (knownUser != null && sameTarget(user, knownUser)) null else user
        val finalPass = when {
            knownPass != null && sameTarget(pass, knownPass) -> null
            // 单行里带着分隔符的，再拆一次
            pass != null && finalUser != null && splitInline(pass).size == 2 -> null
            else -> pass
        }
        return Triple(finalUser, finalPass, title)
    }

    /**
     * 一行里用空格/制表符/`----`/`|`/`,` 分隔的多个值。
     *
     * `----` 之类必须整体当一个分隔符，否则 `abc----123` 会被拆成
     * `abc`、`123` 之外的空串。
     */
    private fun splitInline(line: String): List<String> =
        line.split(INLINE_SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 去掉协议与尾部路径，只留 `host`（或 `host:port`）。
     *
     * 这样存进库的网址就是自动填充能匹配的形式；用户想看完整链接时
     * 详情页会补上 `https://`。
     */
    private fun cleanUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim()
        s = s.substringAfter("://", s)
        s = s.substringBefore('/')
        s = s.substringBefore('?')
        s = s.substringBefore('#')
        s = s.substringAfter('@') // user:pass@host
        s = s.trimEnd('.')
        if (s.isBlank()) return null
        // 只剩单个词（没有点、不是 localhost、也不是 IP）就不当网址
        val looksLikeHost = s.contains('.') || s.equals("localhost", ignoreCase = true)
        return if (looksLikeHost) s else null
    }

    /**
     * `a` 与 `b` 是否指向同一个东西（用来判重，避免同一行被两边都用）。
     *
     * 只做"去掉协议/大小写"级别的比较，不做模糊匹配。
     */
    private fun sameTarget(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val na = a.trim().lowercase().removePrefix("https://").removePrefix("http://").trimEnd('/')
        val nb = b.trim().lowercase().removePrefix("https://").removePrefix("http://").trimEnd('/')
        return na == nb
    }

    /**
     * 这一行更像站点名还是账号？
     *
     * 判断依据：短、不含数字、含中文。
     * - `淘宝` → true（站点名）
     * - `admin` → false（账号）
     * - `13800138000` → false（手机号）
     * - `我的淘宝` → true
     *
     * 这是启发式，会误判。误判的代价只是预填到错误字段，用户改一下即可，
     * 所以宁可简单可解释，不要堆规则。
     */
    private fun looksLikeSiteName(line: String): Boolean {
        if (line.length !in 1..12) return false
        if (line.any { it.isDigit() }) return false
        if (line.contains('@')) return false
        if (line.any { it.isWhitespace() }) return false
        return line.any { it.code >= 0x2E80 } // CJK 起始区
    }
}
