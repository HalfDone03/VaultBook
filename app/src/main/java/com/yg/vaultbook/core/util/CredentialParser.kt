package com.yg.vaultbook.core.util

/**
 * 把一段"从别处粘过来的文本"拆成 账号 / 密码 / 网址 / 标题。
 *
 * ── 为什么需要它 ─────────────────────────────────────────────
 * 用户实际的记录方式是把东西抄在记事本里，格式五花八门：
 * ```
 * 账号：admin 密码：abc123
 * 用户名 admin 密码 abc123
 * 淘宝  13800138000  p@ssw0rd
 * 【淘宝】admin----abc123
 * 1. 京东 myjd 12345678
 * 平台 账号 密码
 * ---
 * www.taobao.com
 * ```
 * 要求用户先手工拆成四个框再输入，等于把"方便"这件事做反了。
 * 所以让 App 自己认。
 *
 * ── 设计取舍 ─────────────────────────────────────────────────
 * 纯函数、零 Android 依赖 —— 这样才能在 JVM 上把各种脏格式都测一遍。
 *
 * 识别一路从"最确定"走到"最含糊"：
 * 1. `key: value`（冒号/等号，含中英文近义词表）；
 * 2. `key value`（**只有空白**分隔，`用户名 admin` 这种最常见的手抄格式）；
 * 3. 整段里找网址；
 * 4. 剩下的行去掉序号、丢掉表头与说明行，再按位置猜；
 * 5. 猜之前先试「名称紧贴值」（`云盘账户密码Zx8Kq2Lm7V`）与「账号:密码」。
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
        /**
         * 备注。
         *
         * 表格里的「备注」列、JSON 的 `note` 字段、`备注：xxx` 这一行都归到这里。
         * 早先这些内容被当作"要丢弃的字段名"整行吃掉，用户的原话是
         * 「备注几乎都识别不出来」—— 备注里常写着"密保问题答案在邮箱"这类
         * 关键提示，丢了就真丢了。
         */
        val notes: String? = null,
    ) {
        /** 一个字段都没认出来。 */
        val isEmpty: Boolean
            get() = username == null && password == null && url == null &&
                title == null && notes == null

        /**
         * 至少认出了账号或密码。
         *
         * 只认出网址不算数 —— 那多半是用户复制了一个链接，
         * 弹"检测到账号密码"会显得很蠢。
         */
        val hasCredential: Boolean
            get() = !username.isNullOrBlank() || !password.isNullOrBlank()

        /**
         * 账号和密码对调。
         *
         * 猜位置这件事本身就不可能全对：`抖音 abc123` 到底是
         * "账号抖音、密码 abc123"还是反过来，只有用户自己知道。
         * 与其把启发式调得更聪明，不如给一个一键对调的按钮，
         * 让猜错的时候有路可走。
         */
        fun swapped(): Result = copy(username = password, password = username)
    }

    // ── 字段名表 ────────────────────────────────────────────────
    // 长的放前面：同一起点上，交替分支是"先匹配到的赢"，
    // 若 `user` 排在 `username` 前面，`username:` 会被切成 `user` + `name:`
    private const val USER_LABELS =
        "登录账号|登录名|用户名|用户账号|用户|账号|帐号|账户|手机号码|手机号|" +
            "邮箱地址|邮箱|username|userid|account|login|email|phone|mobile|user"

    private const val PASS_LABELS =
        "登录密码|支付密码|交易密码|password|passwd|passcode|pwd|secret|密码|口令"

    private const val URL_LABELS =
        "website|domain|网址|网站|链接|地址|url|site|link|host"

    private const val TITLE_LABELS =
        "application|platform|service|title|app名|标题|名称|站点|平台|应用|软件|site|app|name"

    private const val NOTES_LABELS =
        "备注信息|备注|说明|注释|description|remark|notes|note|memo"

    /**
     * 认得出、但**不是凭据**的字段名：序号、分类、类型。
     *
     * 这些行必须被"吃掉"，否则 `序号：3` 会被当成"账号叫『序号』、
     * 密码叫『3』"记一条。
     *
     * 备注曾经也在这张表里，后来单独拆成 [NOTES_LABELS]：备注要留下，不要丢。
     */
    private const val IGNORE_LABELS =
        "index|序号|编号|分类|类型|标签"

    /**
     * 前面不能粘着字母数字：`demo_user:abc` 里的 `user` 是账号的一部分，
     * 不是字段名。少了这个边界，整条链接的账号会被切成
     * `Demo%40123@example.com/login`（越界取值）。
     */
    private val KV_REGEX = Regex(
        "(?i)" +
            "(?<![\\w@.\\-])" +
            "(?:(?<user>$USER_LABELS)" +
            "|(?<pass>$PASS_LABELS)" +
            "|(?<url>$URL_LABELS)" +
            "|(?<title>$TITLE_LABELS)" +
            "|(?<notes>$NOTES_LABELS)" +
            "|(?<ignore>$IGNORE_LABELS))" +
            "\\s*[:：=＝]\\s*"
    )

    /**
     * 只有空白分隔的 `key value`：`用户名 admin`、`密码 abc123`、`PASSWORD 123456`。
     *
     * 记事本里手抄时很多人是不打冒号的，早先只认冒号，于是这种整行被当成账号。
     * 前面的边界要求"行首或紧跟在空白/标点后面"，避免把 `我的密码abc` 里的
     * 「密码」也当成字段名。
     */
    private val BARE_KV_REGEX = Regex(
        "(?i)" +
            "(?:^|(?<=[\\s，,；;|、。]))" +
            "(?:(?<user>$USER_LABELS)" +
            "|(?<pass>$PASS_LABELS)" +
            "|(?<url>$URL_LABELS)" +
            "|(?<title>$TITLE_LABELS)" +
            "|(?<notes>$NOTES_LABELS)" +
            "|(?<ignore>$IGNORE_LABELS))" +
            "\\s+"
    )

    /** 任意协议的链接。早先只认 `http(s)://`，`ftp://user:pass@host` 整行被当成账号。 */
    private val URL_REGEX = Regex(
        "[a-z][a-z0-9+.\\-]*://[^\\s\"'<>）)】]+",
        RegexOption.IGNORE_CASE
    )

    /** `https://user:pass@host` 里带凭据的写法（抓取工具导出的东西常见）。 */
    private val URL_USERINFO_REGEX = Regex("(?i)\\b[a-z][a-z0-9+.\\-]*://([^/\\s:@]+):([^/\\s@]+)@")

    private val HOST_REGEX = Regex(
        "(?i)\\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24}\\b"
    )

    /**
     * JSON 里的一条对象：`{"platform":"微博","account":"x","password":"y"}`。
     *
     * `{` 与 `}` 之间不允许再嵌套，长度也设了上限 —— 这是"记事本里粘的导出
     * 数据"，不是任意 JSON 解析器；把整份大文件当输入时不能回溯到卡死。
     */
    private val JSON_OBJECT_REGEX = Regex("\\{[^{}]{0,4000}\\}")

    /**
     * JSON 里的一个键值对。值支持带引号的字符串、数字、true/false/null。
     *
     * 刻意不用 `org.json`：那是 Android SDK 里的桩实现，JVM 单测一调就抛
     * "not mocked"，而识别逻辑必须在单测里跑得起来。
     */
    private val JSON_FIELD_REGEX = Regex(
        "\"([^\"]{1,40})\"\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.){0,400})\"|(-?[0-9][0-9.eE+\\-]*))"
    )

    /** 表格里的分隔符候选，按"谁更像列分隔符"的顺序试。 */
    private val TABLE_DELIMITERS = listOf(',', '\t', ';', '|')

    /** 表头/JSON 键名归一化后能认出的列。 */
    private enum class Column { TITLE, USER, PASS, URL, NOTES, IGNORE }

    private val USER_KEYS = USER_LABELS.split('|').toSet()
    private val PASS_KEYS = PASS_LABELS.split('|').toSet()
    private val TITLE_KEYS = TITLE_LABELS.split('|').toSet()
    private val URL_KEYS = URL_LABELS.split('|').toSet()
    private val NOTES_KEYS = NOTES_LABELS.split('|').toSet()
    private val IGNORE_KEYS = IGNORE_LABELS.split('|').toSet()

    /** 一行内多个值之间的分隔符。`----` 在抄密码时很常见，必须先于 `-` 处理。 */
    private val INLINE_SEPARATORS = Regex("[ \\t\\u3000]+|_{2,}|-{2,}|\\|+|,|，|;|；|/+")

    /** 连写行里紧贴在值前面的字段名：`云盘账户密码Zx8` 的「密码」。 */
    private val PASSWORD_LABEL_SUFFIX = Regex(
        "(登录密码|支付密码|密码|口令|password|passwd|pwd)$",
        RegexOption.IGNORE_CASE
    )

    /** 同上，但标明后面跟的是账号：`支付宝账号13800138000` 的「账号」。 */
    private val USER_LABEL_SUFFIX = Regex(
        "(登录账号|用户名|用户账号|账号|帐号|账户|手机号码|手机号|username|account)$",
        RegexOption.IGNORE_CASE
    )

    /**
     * 连写行左侧把字段名写在最前面：`账号admin密码abc123` 剥掉「密码」后
     * 剩下的 `账号admin` 其实是「标签 + 账号值」。
     */
    private val USER_LABEL_PREFIX = Regex(
        "^(?:账号|帐号|账户|用户名|登录名|username|account)([A-Za-z0-9_.@+\\-]{2,})$",
        RegexOption.IGNORE_CASE
    )

    /** 行首序号：`1.`、`2、`、`(3)`、`①`、`第4条`。`1.5` 这种小数不算。 */
    private val LINE_INDEX_PREFIX = Regex(
        "^(?:(?:[(（]?\\d{1,3}[)）])|(?:[(（]?\\d{1,3}[.．、,，:：](?!\\d))|(?:第[0-9一二三四五六七八九十]{1,3}[条组个行])|[①②③④⑤⑥⑦⑧⑨⑩⑪⑫])[\\s、.．:：]*"
    )

    /** 记事本里当分隔线用的整行横线。 */
    private val SEPARATOR_LINE = Regex("^[-=*_~#—─━]{3,}$")

    /** 表头/说明行里允许出现的词。整行只由这些词组成时，它是表头，不是记录。 */
    private val HEADER_WORDS = setOf(
        "平台", "软件", "应用", "app", "站点", "网站", "网址", "名称", "标题", "title", "name",
        "账号", "帐号", "账户", "用户名", "登录名", "user", "username", "account", "login",
        "密码", "口令", "password", "passwd", "pwd", "pass", "secret",
        "手机", "手机号", "手机号码", "邮箱", "邮箱地址", "email", "phone", "mobile",
        "备注", "说明", "note", "remark", "序号", "编号", "no", "id", "类型", "type", "分类"
    )

    /** 一行里既没空格也没冒号的「名称 + 值」连写，拆开后的样子。 */
    private data class MixedLine(
        val head: String,
        val value: String,
        val isPassword: Boolean,
        /** 左侧自带 `账号admin` 时拆出来的账号。 */
        val user: String? = null
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
        var notes: String? = null

        // ── 第零步：结构化形状 ────────────────────────────────────
        // JSON 对象和表格行里字段名是显式的，交给下面按位置猜的规则，
        // 只会把引号、逗号当成值的一部分（`username="account":"x"` 那种鬼东西）。
        jsonRecord(text)?.takeIf { it.hasCredential }?.let { return it }
        tableRecords(text).firstOrNull()?.takeIf { it.hasCredential }?.let { return it }

        // 一行的 `标签:账号:密码`（`微信:demo_wx_001:Wx@123456`）。
        // 必须排在 key:value 前面 —— `邮箱:demo@x.com:Mail#2024` 里的「邮箱」
        // 本身就是字段名，先走 kv 会把整个 `demo@x.com:Mail#2024` 当成账号。
        val chainLines = text.split('\n')
            .map { it.stripLineIndex().edgeTrim() }
            .filter { it.isNotEmpty() }
        if (chainLines.size >= 2 && chainLines.all { splitColonChain(it) != null }) {
            parse(chainLines.first())?.let { return it }
        }
        chainLines.singleOrNull()?.let { splitColonChain(it) }?.let { chain ->
            val single = Result(
                username = chain.second,
                password = chain.third,
                url = null,
                title = chain.first,
                notes = null
            )
            if (single.hasCredential) return single
        }

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
            val value = text.substring(valueStart, valueEnd).cleanValue()
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
                match.groups["notes"] != null && notes == null -> {
                    notes = value.ifBlank { null }
                }
            }
            consumedLines += lineIndex
            searchFrom = if (valueEnd > valueStart) valueEnd else valueStart
        }

        // ── 第二步：key value（只有空白）────────────────────────────
        // 有冒号的写法上面已经吃掉，这里补"不打冒号"的：`用户名 admin`、`密码 abc123`。
        var bareFrom = 0
        while (true) {
            val match = BARE_KV_REGEX.find(text, bareFrom) ?: break
            val valueStart = match.range.last + 1
            val lineEnd = text.indexOf('\n', valueStart).let { if (it < 0) text.length else it }
            val next = BARE_KV_REGEX.find(text, valueStart)
            val valueEnd = if (next != null && next.range.first < lineEnd) next.range.first else lineEnd
            val value = text.substring(valueStart, valueEnd).cleanValue()
            // 冒号写法之外，值完全可能是下一个字段名（`账号 密码 abc`），
            // 那种情况说明这一行没有值，跳过而不是把字段名当值。
            val usable = value.takeIf { it.isNotBlank() && !isLabelWord(it) }
            val lineIndex = text.take(match.range.first).count { it == '\n' }
            // `密码` 独占一行、值在下一行时，`\s+` 已经吃掉了换行，
            // 值所在的那一行也要标记成"已用过"，否则它会被再当成账号猜一次。
            val valueLine = text.take(valueStart).count { it == '\n' }

            when {
                match.groups["user"] != null && username == null -> username = usable
                match.groups["pass"] != null && password == null -> password = usable
                match.groups["url"] != null && url == null -> {
                    url = usable?.let { cleanUrl(it) ?: it }
                }
                match.groups["title"] != null && title == null -> title = usable
                match.groups["notes"] != null && notes == null -> notes = usable
            }
            consumedLines += lineIndex
            if (valueLine != lineIndex) consumedLines += valueLine
            bareFrom = if (valueEnd > valueStart) valueEnd else valueStart
        }

        // ── 第三步：链接里的凭据 ─────────────────────────────────
        // 抓包/抓取工具导出的东西常写成 `https://user:pass@host`。
        // 这一步必须排在"整段里找网址"前面：否则整行先被当成网址吃掉，
        // 账号会变成 `Demo%40123@example.com/login` 这种残渣。
        // 逐行找而不是全段找 —— 一次粘好几条链接时，全段只能捞到第一条。
        if (username == null || password == null) {
            for (line in text.split('\n')) {
                val m = URL_USERINFO_REGEX.find(line) ?: continue
                if (username == null) username = percentDecode(m.groupValues[1]).ifBlank { null }
                if (password == null) password = percentDecode(m.groupValues[2]).ifBlank { null }
                if (url == null) url = cleanUrl(line)
                if (username != null && password != null) break
            }
        }

        // ── 第四步：从整段里找网址（哪怕没有 url 这个 key）──────────
        if (url == null) {
            URL_REGEX.find(text)?.let { url = cleanUrl(it.value) }
        }
        if (url == null) {
            // 裸域名。要排除 kv 已被用掉的用户名/密码，也要排除邮箱 ——
            // 否则 `demo.gmail@example.com` 会先把 `demo.gmail` 当成站点。
            url = firstHostOutsideEmail(text, listOfNotNull(username, password))
        }

        // ── 第五步：剩下的行按位置猜 ─────────────────────────────
        val leftovers = text.split('\n')
            .mapIndexed { index, line -> index to line.stripLineIndex().edgeTrim() }
            .filter { (index, line) ->
                line.isNotEmpty() &&
                    index !in consumedLines &&
                    !isHeaderLine(line) &&
                    !isSeparatorLine(line)
            }
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
                if (mixed.user != null && username == null) username = mixed.user
                // 账号已经从左侧拆出来了，那半截「账号admin」不配当标题
                if (title == null && mixed.user == null) title = mixed.head
            } else {
                // 「账号:密码」连写：`zhang@qq.com:abc123`、`admin:p@ssw0rd`
                // 三个冒号的「标签:账号:密码」（`微信:demo_wx:Wx@123`）也在这里认。
                val chain = leftovers.singleOrNull()?.let { splitColonChain(it) }
                val colonPair = if (chain == null) {
                    leftovers.singleOrNull()?.let { splitAccountColonPassword(it) }
                } else {
                    null
                }
                if (chain != null) {
                    if (title == null) title = chain.first
                    if (username == null) username = chain.second
                    if (password == null) password = chain.third
                } else if (colonPair != null && username == null && password == null) {
                    username = colonPair.first
                    password = colonPair.second
                } else {
                    val guess = guessFromLeftovers(leftovers, username, password)
                    if (username == null) username = guess.first
                    if (password == null) password = guess.second
                    if (title == null) title = guess.third
                }
            }
        }

        // 标题还空着、但剩下的行里有一行干干净净的平台名（`淘宝`），就拿它当标题。
        // 这一步排在"按位置猜"之后：猜出来的标题更可信，只有没猜着时才补。
        if (title == null) {
            title = leftovers.firstOrNull { isPlainSiteName(it) }
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
            title = title?.trim()?.ifBlank { null },
            notes = notes?.trim()?.ifBlank { null }
        )
        return if (result.isEmpty) null else result
    }

    /**
     * 一段文字里可能抄着好几组账号密码（记事本里一行一条地记）。
     * 按"最可信的形状先试"依次判断，认不出多条就退回单条：
     *
     * 0. 整行横线（`---`、`===`、`***`）当分隔，一段一条；
     * 1. 每行都是「名称紧贴值」的连写（`云盘账户密码Zx8Kq2Lm7V`）→ 一行一条；
     * 2. 每行都是多列（`淘宝 账号 密码` 一行一条）→ 一行一条；
     * 3. 空行分段，每段自成一册 → 一段一条；
     * 4. 关键字段重复出现（`平台：A 账号：x 密码：y 平台：B …`）→ 在每个重复的
     *    标题/账号键处切开，**只在前一条已经有密码之后才切**，否则
     *    `平台：A` `账号：x` `密码：y` 三行会被拆成三条；
     * 5. 都不成立 → 单条。
     *
     * 预处理会先剥掉行首序号（`1.`、`②`）、丢掉表头行（`平台 账号 密码`）
     * 与整行分隔线，否则这些行会被当成账号填进第一条。
     *
     * 只在结果 ≥2 且每条都有凭据时才认为"识别到多条"。少拆比多拆好：
     * 拆错会让用户看到一堆半条记录，比只识别一条更劝退。
     */
    fun parseMany(raw: String?): List<Result> {
        val single = parse(raw)?.takeIf { it.hasCredential }?.let { listOf(it) } ?: emptyList()
        if (raw.isNullOrBlank()) return emptyList()

        val text = raw.replace("\r\n", "\n").replace('\r', '\n')

        // 0) JSON 数组 / 一整张表：字段名是显式的，别再让"按位置猜"的规则去猜
        jsonRecords(text)?.let { if (it.size >= 2) return it }
        tableRecords(text).let { if (it.size >= 2) return it }

        // 1) 分隔线分段：`---` 之类在记事本里就是"下一条"的意思
        val sections = splitBySeparator(text)
        if (sections.size >= 2) {
            val parsed = sections.mapNotNull { parse(it) }.filter { it.hasCredential }
            if (parsed.size >= 2) return parsed
        }

        val lines = text.split('\n')
            .map { it.stripLineIndex().edgeTrim() }
            .filter { it.isNotEmpty() && !isSeparatorLine(it) && !isHeaderLine(it) }

        // 1) 一行一条的「连写」：`云盘账户密码Zx8Kq2Lm7V` 这种既没空格也没冒号，
        //    下面几条规则全都认不出（每行切不出 ≥2 段、没有空行、没有重复的 key），
        //    只会退化成一条错的 —— 标题丢了，账号变成整行，后面的行整段消失。
        if (lines.size >= 2 && lines.all { splitMixed(it) != null }) {
            val parsed = lines.mapNotNull { parse(it) }.filter { it.hasCredential }
            if (parsed.size >= 2) return parsed
        }

        // 2) 一行一条「标签:账号:密码」：`微信:demo_wx_001:Wx@123456`。
        //    三个冒号的形状以前一条都认不出 —— 整行被当账号、后面的行全丢。
        if (lines.size >= 2 && lines.all { splitColonChain(it) != null }) {
            val parsed = lines.mapNotNull { parse(it) }.filter { it.hasCredential }
            if (parsed.size >= 2) return parsed
        }

        // 2) 一行一条：每行都能切出 ≥2 段
        if (lines.size >= 2 && lines.all { splitInline(it).size >= 2 }) {
            val parsed = lines.mapNotNull { parse(it) }.filter { it.hasCredential }
            if (parsed.size >= 2) return parsed
        }

        // 3) 空行分段。要求每一段都认出了密码 —— 只有一段写着「账号：x」、
        //    另一段写着「密码：y」时，那是同一条记录被空行拆开，不是两条。
        val blocks = text.split(Regex("\n\\s*\n+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (blocks.size >= 2) {
            val parsed = blocks.mapNotNull { parse(it) }.filter { it.hasCredential }
            if (parsed.size >= 2 && parsed.all { it.password != null }) return parsed
        }

        // 4) 重复 key：在"上一条已有密码之后又出现标题/账号键"的地方切
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

    /** 按整行横线切段；没有横线时返回整段本身。 */
    private fun splitBySeparator(text: String): List<String> {
        val sections = arrayListOf<String>()
        val current = StringBuilder()
        text.split('\n').forEach { line ->
            if (isSeparatorLine(line.stripLineIndex().edgeTrim())) {
                val block = current.toString().trim()
                if (block.isNotEmpty()) sections.add(block)
                current.setLength(0)
            } else {
                current.append(line).append('\n')
            }
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) sections.add(tail)
        return sections
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

        // 「账号admin密码abc123」：剥掉尾部「密码」剩下的「账号admin」
        // 其实是「标签 + 账号值」，再拆一次，别让「账号admin」变成标题。
        if (isPassword) {
            USER_LABEL_PREFIX.matchEntire(name)?.let { m ->
                return MixedLine(name, tail, true, m.groupValues[1])
            }
        }

        return MixedLine(name, tail, isPassword)
    }

    /**
     * `zhang@qq.com:abc123` / `admin:p@ssw0rd` —— 账号与密码用一个冒号直接连着。
     *
     * 左边是中文就不是这个形状（那是「标题：值」，交给 key: value）；
     * 右边是网址也不是（那是网址 key 漏网）。
     */
    private fun splitAccountColonPassword(line: String): Pair<String, String>? {
        if (line.isEmpty() || line.any { it.isWhitespace() }) return null
        if (line.count { it == ':' || it == '：' } != 1) return null
        val idx = line.indexOfFirst { it == ':' || it == '：' }
        val left = line.substring(0, idx)
        val right = line.substring(idx + 1)
        if (left.isEmpty() || right.isEmpty()) return null
        if (left.any { it.code >= 0x2E80 }) return null
        if (left.any { it in "=＝" }) return null
        if (!right.any { it.isLetterOrDigit() }) return null
        if (cleanUrl(right) != null) return null
        return left to right
    }

    /**
     * 从没用掉的残行里推断 (账号, 密码, 标题)。
     *
     * 四种典型形状：
     * - 只剩一行 `admin 123456`  → 按分隔符切成两个
     * - 剩两行 `淘宝` / `abc123` → 首行像站点名时它是标题，第二行才当值
     * - 剩两行 `admin` / `123456` → 谁都不像站点名，按账号密码依次填
     * - 剩三行 → 若第一行像站点名（"淘宝"而不是"admin"），它是标题
     */
    private fun guessFromLeftovers(
        lines: List<String>,
        knownUser: String?,
        knownPass: String?
    ): Triple<String?, String?, String?> {
        if (lines.isEmpty()) return Triple(null, null, null)

        if (lines.size == 1) {
            // 行里混着网址时先把网址那一段剔掉，否则 `admin abc123 www.a.com`
            // 会变成账号=abc123、密码=www.a.com
            val parts = splitInline(lines[0]).filterNot { cleanUrl(it) != null }
            val guess = when {
                parts.size >= 3 -> Triple(parts[1], parts[2], parts[0])
                // 只有两段时想当然当成「账号 密码」会出错：`抖音 12313213`
                // 里第一段明明是平台名。平台名从不带数字，值通常带，
                // 所以据此把两段判成「标题 账号」。
                parts.size == 2 -> {
                    val head = parts[0]
                    val tail = parts[1]
                    // 两段全是汉字（`项目,说明`、`淘宝,购物用`）多半是标签列或一句
                    // 说明，不是账号密码。真实的账号或密码至少带字母/数字/符号。
                    if (isPlainSiteName(head) && isPlainSiteName(tail)) {
                        Triple(null, null, null)
                    } else if (isPlainSiteName(head) && tail.any { it.isDigit() }) {
                        Triple(tail, null, head)
                    } else {
                        Triple(head, tail, null)
                    }
                }
                parts.isEmpty() -> Triple(null, null, null)
                else -> Triple(if (knownUser == null) parts[0].ifBlank { null } else null, null, null)
            }
            // `抖音12313213 1231313`：平台名和账号贴在一起，直接当账号会变成
            // `抖音12313213`，用户看着就是"认错了"。把平台名剥出来当标题。
            val split = guess.first?.let { splitSiteAndValue(it) }
            if (guess.third == null && split != null) {
                return Triple(split.second, guess.second, split.first)
            }
            return guess
        }

        // 两行：首行是站点名、次行又不像站点名 → 首行当标题，值只看第二行。
        // （`淘宝` + `abc123` 这种抄法里，第一行显然不是账号。）
        if (lines.size == 2) {
            val first = lines[0]
            val second = lines[1]
            if (looksLikeSiteName(first) && !looksLikeSiteName(second)) {
                val valueIsPassword = second.any { it.isDigit() } || second.length >= 8
                return if (valueIsPassword) Triple(null, second, first) else Triple(second, null, first)
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

        // `抖音12313213` 单独占一行的抄法：平台名和账号贴在一起
        val split = if (title == null) splitSiteAndValue(user) else null
        val spacedUser = split?.second ?: user
        val spacedTitle = split?.first ?: title

        // 如果认出来的"账号"其实是已知账号的重复，跳过它
        val finalUser = if (knownUser != null && sameTarget(spacedUser, knownUser)) null else spacedUser
        val finalPass = when {
            knownPass != null && sameTarget(pass, knownPass) -> null
            // 单行里带着分隔符的，再拆一次
            pass != null && finalUser != null && splitInline(pass).size == 2 -> null
            else -> pass
        }
        return Triple(finalUser, finalPass, spacedTitle)
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
     * 把「平台名 + 值」粘在一起的一段切开：`抖音12313213` → (`抖音`, `12313213`)。
     *
     * 为什么需要：`抖音12313213 1231313` 这种抄法按列拆成两段，第一段整串
     * 会被当成账号，用户看到的就是"账号认成了 抖音12313213"。平台名本身
     * 很少带数字、值也很少纯中文，所以只要左边是纯中文短词、右边带数字，
     * 就能安全地拆。
     *
     * 不拆的情况：左边以「账号/密码」这类标签结尾（那是标签不是平台名）、
     * 右边没有数字、左边超过 6 个汉字（太长，多半是整句话）。
     */
    private val SITE_VALUE_SPLIT = Regex("^([\\u4e00-\\u9fa5]{2,6})([A-Za-z0-9][A-Za-z0-9_.@+\\-]{1,})$")

    private fun splitSiteAndValue(raw: String?): Pair<String, String>? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val match = SITE_VALUE_SPLIT.matchEntire(text) ?: return null
        val name = match.groupValues[1]
        val value = match.groupValues[2]
        if (value.none { it.isDigit() }) return null
        if (PASSWORD_LABEL_SUFFIX.containsMatchIn(name)) return null
        if (USER_LABEL_SUFFIX.containsMatchIn(name)) return null
        return name to value
    }

    /** 纯中文短词，看着像平台名（`抖音`、`淘宝`、`医保平台`）。 */
    private fun isPlainSiteName(raw: String): Boolean {
        val text = raw.trim()
        if (text.length !in 2..6) return false
        return text.all { it.code in 0x4E00..0x9FA5 }
    }

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

    /**
     * 值两端的收尾：空白、全角空格、不换行空格，以及粘进来的逗号分号竖线。
     *
     * 只动两端，中间的字符一个不碰 —— 密码里带空格是合法的。
     */
    private fun String.cleanValue(): String =
        trim()
            .trim('\u3000', '\u00A0')
            .trim(',', '，', ';', '；', '|')

    /** 同上，但更保守：只去空白类字符，不去标点。 */
    private fun String.edgeTrim(): String = trim().trim('\u3000', '\u00A0')

    /** 行首序号剥掉：`1. 淘宝 …`、`②淘宝 …`、`第3条 淘宝 …`。 */
    private fun String.stripLineIndex(): String {
        val m = LINE_INDEX_PREFIX.find(this) ?: return this
        val rest = substring(m.range.last + 1)
        return if (rest.isBlank()) this else rest
    }

    /** 整行只有横线：`---`、`===`、`***`、`———`。 */
    private fun isSeparatorLine(line: String): Boolean =
        line.isNotEmpty() && SEPARATOR_LINE.matches(line.filterNot { it.isWhitespace() })

    /**
     * 表头行：`平台 账号 密码`、`网站 用户名 密码 备注`、`site user pass`。
     *
     * 从 Excel 或网页表格里复制时第一行永远是表头。不丢掉的话，
     * 它会变成一条"账号叫『平台』、密码叫『密码』"的假记录。
     */
    private fun isHeaderLine(line: String): Boolean {
        val tokens = line.lowercase()
            .split(Regex("[\\s\\u3000,，;；|/、:：]+"))
            .filter { it.isNotEmpty() }
        if (tokens.size !in 2..6) return false
        return tokens.all { it in HEADER_WORDS }
    }

    /** 这个值是不是又一个字段名（`账号 密码 abc` 里的「密码」）。 */
    private fun isLabelWord(value: String): Boolean {
        val v = value.lowercase().edgeTrim()
        return v in HEADER_WORDS
    }

    // ── 表格与 JSON ─────────────────────────────────────────────

    /**
     * 表头或 JSON 键名属于哪一列。认不出返回 null。
     *
     * 头尾的引号、方括号、括号里的单位（`密码（选填）`）都先剥掉，
     * 因为从 Excel 粘出来的表头经常带这些。
     */
    private fun columnOf(token: String?): Column? {
        if (token == null) return null
        val t = token.trim()
            .trim('"', '\'', '「', '」', '【', '】')
            .substringBefore('(')
            .substringBefore('（')
            .replace(" ", "")
            .trim()
            .lowercase()
        if (t.isEmpty()) return null
        return when {
            t in USER_KEYS -> Column.USER
            t in PASS_KEYS -> Column.PASS
            t in TITLE_KEYS -> Column.TITLE
            t in URL_KEYS -> Column.URL
            t in NOTES_KEYS -> Column.NOTES
            t in IGNORE_KEYS -> Column.IGNORE
            else -> null
        }
    }

    /**
     * 一整张表：第一行是表头，后面每行一条记录。
     *
     * CSV 与制表符表格是同一种东西，只是分隔符不同，所以统一按"挑分隔符"
     * 处理。**必须认出账号列和密码列**才算表格 —— 否则 `a,b` 这种两列文本
     * 也会被当成表，把本来能按位置猜出来的东西拆坏。
     */
    private fun tableRecords(text: String): List<Result> {
        val lines = text.split('\n')
            .map { it.stripLineIndex().edgeTrim() }
            .filter { it.isNotEmpty() && !isSeparatorLine(it) }
        if (lines.size < 2) return emptyList()

        val delimiter = pickDelimiter(lines) ?: return emptyList()
        val header = splitCells(lines[0], delimiter)
        if (header.size < 2) return emptyList()

        val columns = header.map { columnOf(it) }
        if (Column.USER !in columns || Column.PASS !in columns) return emptyList()

        return lines.drop(1).mapNotNull { line ->
            val cells = splitCells(line, delimiter)
            if (cells.size < 2) return@mapNotNull null
            var user: String? = null
            var pass: String? = null
            var title: String? = null
            var url: String? = null
            var notes: String? = null
            columns.forEachIndexed { index, column ->
                val cell = cells.getOrNull(index)?.ifBlank { null } ?: return@forEachIndexed
                when (column) {
                    Column.USER -> if (user == null) user = cell
                    Column.PASS -> if (pass == null) pass = cell
                    Column.TITLE -> if (title == null) title = cell
                    Column.URL -> if (url == null) url = cleanUrl(cell) ?: cell
                    Column.NOTES -> if (notes == null) notes = cell
                    Column.IGNORE, null -> Unit
                }
            }
            Result(username = user, password = pass, url = url, title = title, notes = notes)
                .takeIf { it.hasCredential }
        }
    }

    /** 前几行都含有的那个分隔符。都有多个时按 [TABLE_DELIMITERS] 的顺序取第一个。 */
    private fun pickDelimiter(lines: List<String>): Char? {
        val sample = lines.take(4)
        return TABLE_DELIMITERS.firstOrNull { delimiter ->
            sample.all { it.count { ch -> ch == delimiter } >= 1 } &&
                sample.first().count { ch -> ch == delimiter } >= 1
        }
    }

    /** 按分隔符切单元格，顺手剥掉 CSV 的引号。 */
    private fun splitCells(line: String, delimiter: Char): List<String> =
        line.split(delimiter).map { cell ->
            val t = cell.trim()
            val quoted = t.length >= 2 &&
                ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")))
            if (quoted) t.substring(1, t.length - 1).trim() else t
        }

    /**
     * JSON 对象里的一条记录。只取第一个 `{...}` —— 多个对象是"多条"，
     * 那是 [parseMany] 的事，[parse] 只负责一条。
     *
     * 刻意不用 `org.json`：它是 Android SDK 里的桩，JVM 单测一调就抛
     * "not mocked"，而识别逻辑必须在单测里跑得起来。
     */
    private fun jsonRecord(text: String): Result? {
        val scope = JSON_OBJECT_REGEX.find(text)?.value ?: text
        if (!scope.contains(':')) return null
        var user: String? = null
        var pass: String? = null
        var title: String? = null
        var url: String? = null
        var notes: String? = null
        var recognized = false
        JSON_FIELD_REGEX.findAll(scope).forEach { match ->
            val key = match.groupValues[1]
            val raw = match.groupValues[2].ifBlank { match.groupValues[3] }
            val value = unescapeJson(raw).ifBlank { null } ?: return@forEach
            when (columnOf(key)) {
                Column.USER -> if (user == null) user = value
                Column.PASS -> if (pass == null) pass = value
                Column.TITLE -> if (title == null) title = value
                Column.URL -> if (url == null) url = cleanUrl(value) ?: value
                Column.NOTES -> if (notes == null) notes = value
                Column.IGNORE, null -> Unit
            }
            recognized = true
        }
        if (!recognized) return null
        return Result(username = user, password = pass, url = url, title = title, notes = notes)
            .takeIf { it.hasCredential }
    }

    /** 一段文字里的多个 JSON 对象（≥2 条记录才返回）。 */
    private fun jsonRecords(text: String): List<Result>? {
        if (!text.contains('{')) return null
        val objects = JSON_OBJECT_REGEX.findAll(text).map { it.value }.toList()
        if (objects.size < 2) return null
        val records = objects.mapNotNull { jsonRecord(it) }.filter { it.hasCredential }
        return records.takeIf { it.size >= 2 }
    }

    /** JSON 字符串里的转义还原。只处理常见的几个，够了。 */
    private fun unescapeJson(raw: String): String {
        if (!raw.contains('\\')) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val next = raw[i + 1]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        val hex = raw.substring(i + 2, minOf(i + 6, raw.length))
                        val code = hex.toIntOrNull(16)
                        if (code != null) {
                            sb.append(code.toChar())
                            i += 6
                            continue
                        }
                        sb.append(next)
                    }
                    else -> sb.append(next)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    // ── 一行三段的「标签:账号:密码」 ───────────────────────────

    /** 这些词是"字段名"而不是平台名，不能拿来当标题。 */
    private val GENERIC_USER_WORDS = setOf(
        "账号", "帐号", "账户", "用户名", "登录名", "用户",
        "account", "username", "user", "userid", "login"
    )

    /**
     * `微信:demo_wx_001:Wx@123456` —— 一行里两个冒号，依次是标签、账号、密码。
     *
     * 这种形状早先什么规则都不匹配：认不出字段名、没有空白、冒号又不是一个
     * （[splitAccountColonPassword] 要求恰好一个），于是整行被当成账号，
     * 后面的行整段消失。用户的原话是「这种识别不出来」。
     */
    private fun splitColonChain(line: String): Triple<String?, String?, String?>? {
        if (line.contains("://")) return null
        if (line.any { it.isWhitespace() }) return null
        val parts = line.split(':', '：').map { it.trim() }
        if (parts.size < 3) return null
        if (parts.any { it.isEmpty() }) return null

        val head = parts[0]
        val kind = columnOf(head)
        val user = parts[1]
        val pass = parts.drop(2).joinToString(":")

        val title: String? = when (kind) {
            // `账号:admin:abc123`：头是字段名，不是平台名
            Column.USER -> if (head.lowercase() in GENERIC_USER_WORDS) null else head
            Column.TITLE -> head
            null -> {
                // 既不是字段名，就得像个平台名：纯中文短语，或全是字母的英文站名
                if (!isPlainSiteName(head) && head.any { it.code < 0x2E80 }) return null
                head
            }
            else -> return null
        }
        return Triple(title, user, pass)
    }

    // ── 网址 ────────────────────────────────────────────────────

    /** 第一个"不在邮箱里"的裸域名。邮箱两侧的 host 段都要跳过。 */
    private fun firstHostOutsideEmail(text: String, ignore: List<String>): String? {
        for (match in HOST_REGEX.findAll(text)) {
            val before = text.getOrNull(match.range.first - 1)
            val after = text.getOrNull(match.range.last + 1)
            if (before == '@' || after == '@') continue
            val value = match.value
            if (ignore.any { it == value }) continue
            if (value.endsWith(".apk", ignoreCase = true)) continue
            return value
        }
        return null
    }

    /**
     * 链接 userinfo 里的百分号编码还原：`Demo%40123` → `Demo@123`。
     *
     * 这一步是"还原原文"而不是"改值" —— 链接里的账号密码本来就是编码过的，
     * 存 `Demo%40123` 拿去登录必然失败。只认 `%` 后跟两位合法十六进制的形式，
     * 其他一律原样留着。
     */
    private fun percentDecode(raw: String): String {
        if (!raw.contains('%')) return raw
        val pending = java.io.ByteArrayOutputStream()
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '%' && i + 2 < raw.length) {
                val code = raw.substring(i + 1, i + 3).toIntOrNull(16)
                if (code != null) {
                    pending.write(code)
                    i += 3
                    continue
                }
            }
            if (pending.size() > 0) {
                out.append(String(pending.toByteArray(), Charsets.UTF_8))
                pending.reset()
            }
            out.append(c)
            i++
        }
        if (pending.size() > 0) {
            out.append(String(pending.toByteArray(), Charsets.UTF_8))
        }
        return out.toString()
    }
}
