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
    // 长的放前面：同一起点上交替分支是"先匹配到的赢"。用户编号／用户id／用户号
    // 必须排在裸 `用户` 前面，否则 `用户ID：a1` 只吃到 `用户`、剩下的 `ID` 让整条匹配失败。
    private const val USER_LABELS =
        "登录账号|登陆账号|登录账户|登陆账户|登录帐号|登陆帐号|登录名|登陆名|用户名|用户账号|用户编号|微信号|账号名|帐号名|账户名|会员名|会员账号|" +
            "账号|帐号|账户|帳號|會員號|会员号|卡号|qq号|qq邮箱|用户id|用户号|用户|" +
            "手機號碼|手机号码|手机号|手机|电子邮箱|電子郵箱|邮箱地址|邮箱|" +
            "username|user_name|user-name|userid|accountname|account_name|account(?:\\s?id)?|acct|loginname|login_name|login_id|login(?:\\s?id)?|appid|appkey|usr|" +
            "email|phone|mobile|qq|user"

    /**
     * `pass` 放在最后：`password`/`passwd`/`passcode` 都比它长，
     * 交替分支先试长的，`pass` 只兜住 JDBC 串、JSON 键名那种裸 `pass=`
     * （`{"site":"Steam","user":"x","pass":"y"}` 早先认不出密码就是因为漏了它）。
     *
     * `确认密码`/`重复密码` 也算密码：表单里两遍都抄下来时，不认这一行
     * 会让第一遍的密码一直吃到行尾（`Rp@111111 确认密码 Rp@111111`）。
     */
    private const val PASS_LABELS =
        "登录密码|登陆密码|支付密码|交易密码|服务密码|查询密码|二次密码|确认密码|重复密码|確認密碼|" +
            "授权码|授权密码|独立密码|专用密码|辅助密码|动态密码|安全码|" +
            "登录口令|支付口令|pass_password|pass_word|pass-word|pass\\s?word|passwd|passcode|password|" +
            "confirmpassword|repeatpassword|appsecret|appsecretkey|pwd|secret|密码|密碼|口令|pass"

    /**
     * `网站`、`site` 不在这张表里 —— 它们在手抄笔记里几乎总是指"哪个平台"
     * （`网站：淘宝`、`Site: Steam`），放进网址列会被当成网址收下、标题留空。
     * 真正指链接的是 `网址`、`url`、`link`、`host`。
     */
    private const val URL_LABELS =
        "website|domain|网址|链接|地址|url|link|host"

    /**
     * `网站`、`site` 从网址表挪到了这里 —— 手抄笔记里 `网站：淘宝`、`Site: Steam`
     * 指的是"哪个平台"，放进网址列会被当成链接收下、标题留空。
     * 真正指链接的是 `网址`、`url`、`link`、`host`、`地址`。
     */
    private const val TITLE_LABELS =
        "application|platform|service|title|app名|标题|名称|站点|平台|平台名|平台名称|站点名|应用名|应用名称|应用|软件|site|app|name|网站"

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
    // `昵称` 不是账号（微信导出的三行里它排第一，会挡住真正的「微信号」）。
    private const val IGNORE_LABELS =
        "index|序号|编号|分类|类型|标签|昵称|nickname"

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
            // 字段名后面常跟一段说明：`账号（登录用）：ex_1`、`密码(区分大小写)=…`
            "(?:\\s*[（(][^）)\\n]{0,20}[）)])?" +
            // 字段名后面常跟个序号：`账号1：`、`密码2：`、`password1=`
            "[0-9]{0,3}" +
            "[\\s\u3000]*[:：=＝][\\s\u3000]*"
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
            // 反引号里的括号也认：`「淘宝」账号 taobao_14` 里「账号」前面是 `」`，
            // 早先只认空白和半角标点，于是账号整行没人要，只捞到一个密码。
            // 全角空格 U+3000 也算边界：`平台　知乎　账号　zh_1` 里每个字段名前都是它，
            // `\s` 不认 U+3000，漏了的话第一个字段会一路吃到行尾。
            "(?:^|(?<=[\\s\\u3000，,；;|、。」』】》\"'\\]\\)）]))" +
            "(?:(?<user>$USER_LABELS)" +
            "|(?<pass>$PASS_LABELS)" +
            "|(?<url>$URL_LABELS)" +
            "|(?<title>$TITLE_LABELS)" +
            "|(?<notes>$NOTES_LABELS)" +
            "|(?<ignore>$IGNORE_LABELS))" +
            "[0-9]{0,3}" +
            // 全角空格也是分隔：中文输入法下按空格常常打出来的是 U+3000，
            // Kotlin 的 `\s` 不认它，`账号　zx_1` 会整行没人要。
            "[\\s\\u3000]+"
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
    private val TABLE_DELIMITERS = listOf(',', '\t', ';', '|', '｜', '、')

    /**
     * XML 里成对的标签：`<platform>微信</platform>`。
     *
     * 只认"紧挨着的一对"，中间不许再夹标签 —— 账号导出文件的结构就那么浅，
     * 认得太深反而会把整段 XML 当成一个值。
     */
    private val XML_PAIR_REGEX = Regex("<([A-Za-z_][\\w.\\-]{0,30})>([^<>]{0,500})</\\1>")

    /** HTML 表格的一行：`<tr>…</tr>`，里面再抠 `<td>`。从网页复制表格时剪贴板常带这个。 */
    private val HTML_ROW_REGEX = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** HTML 表格的一格：`<td>淘宝</td>`、`<td class="x">淘宝</td>`。 */
    private val HTML_CELL_REGEX = Regex("<t[dh][^>]*>(.*?)</t[dh]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** `平台(淘宝) 账号(xx) 密码(yy)` —— 值被括号包起来的写法。 */
    private val PAREN_PAIR_REGEX = Regex(
        "([\\u4e00-\\u9fa5A-Za-z_][\\u4e00-\\u9fa5A-Za-z0-9_]{0,20})\\s*[(（]([^()（）\\n]{1,200})[)）]"
    )

    /** 一行自成一条、行首写着列名时可能用到的分隔符，按"切得最碎"挑一个。 */
    private val ROW_DELIMITERS = charArrayOf('|', '｜', ',', '，', ';', '；', '、', '\t', '&')

    /**
     * 行首列名之后的几个值按这个顺序填。
     *
     * 备注排在网址前面：手抄行几乎都是「平台 账号 密码 备注」，
     * 网址露头的情况极少，把第四格当网址反而会得到一条打不开的链接。
     */
    private val ROW_ORDER = listOf(Column.TITLE, Column.USER, Column.PASS, Column.NOTES, Column.URL)

    /** 括号里写着的填表说明，不是值。 */
    private val FORM_HINTS = setOf(
        "必填", "选填", "可选", "选填项", "必填项", "非必填", "可空", "无", "空",
        "required", "optional"
    )

    /** 一段没有空白的英文数字串（账号、手机号、QQ 号都长这样）。 */
    private val ACCOUNT_TOKEN = Regex("^[A-Za-z0-9_.@+\\-]{2,64}$")

    /** 写成邮箱的样子：`a@b.com`。用来把"账号行"和"密码行"分开。 */
    private val EMAIL_SHAPE = Regex("^[^@\\s]{1,64}@[^@\\s]{1,64}\\.[A-Za-z]{2,12}$")

    /**
     * 行首的项目符号：`- 淘宝 …`、`• 知乎 …`、`* 微博 …`。
     *
     * 要求后面必须跟空白，不然 `-123456` 这种以减号开头的密码也会被剥掉。
     * 不剥的话它会占掉第一个位置：标题变成 `-`、账号变成平台名、密码变成账号。
     */
    private val BULLET_PREFIX = Regex("^(?:[•·▪◦‣∙●○◆◇★☆※>]|[-–—*+])[ \\t\\u3000]+")

    /** 书名号/方头括号里包着的平台名：`「淘宝」账号 …`、`【淘宝】`。 */
    private val BRACKET_TITLE = Regex("[「『【《]([^」』】》\\n]{1,20})[」』】》]")

    /**
     * XML 里带属性的自闭合标签：`<entry site="淘宝" user="j1" pass="J#111111"/>`。
     *
     * 导出工具也爱用这种写法，早先只认 `<tag>值</tag>`，属性写法一个字都认不出，
     * 只捞到 `"j1"` 这种带引号的残渣。
     */
    private val XML_TAG_REGEX = Regex(
        "<([A-Za-z_][\\w.\\-]{0,30})" +
            "((?:\\s+[A-Za-z_][\\w.\\-]{0,30}\\s*=\\s*(\"[^\"]{0,400}\"|'[^']{0,400}'))+)\\s*/?>"
    )

    // 单引号也算：手写的 XML / HTML 片段常是 `<e site='淘宝' user='x'/>`。
    private val XML_ATTR_REGEX = Regex(
        "([A-Za-z_][\\w.\\-]{0,30})\\s*=\\s*(?:\"([^\"]{0,400})\"|'([^']{0,400})')"
    )

    /** 连写行里夹着的账号：`微博账号weibo_13密码Wb@131313` 中间那段。 */
    private val EMBEDDED_ACCOUNT = Regex(
        "(?:账号|帐号|账户|用户名|登录名|username|account)([A-Za-z0-9_.@+\\-]{2,})",
        RegexOption.IGNORE_CASE
    )

    /** 表头/JSON 键名归一化后能认出的列。 */
    private enum class Column { TITLE, USER, PASS, URL, NOTES, IGNORE }

    // 标签表既当正则用（找字段名），也当字面集合用（认 JSON 的键、XML 的标签名）。
    // 当集合用的时候要把正则尾巴切掉：`account(?:\s?id)?` 只有前半截是键名，
    // 直接 split 会得到一个永远匹配不上的长串，`"account":"x"` 就认不出。
    private fun literalsOf(labels: String): Set<String> =
        labels.split('|').map { it.substringBefore("(?").trim() }.filter { it.isNotEmpty() }.toSet()

    private val USER_KEYS = literalsOf(USER_LABELS)
    private val PASS_KEYS = literalsOf(PASS_LABELS)
    private val TITLE_KEYS = literalsOf(TITLE_LABELS)
    private val URL_KEYS = literalsOf(URL_LABELS)
    private val NOTES_KEYS = literalsOf(NOTES_LABELS)
    private val IGNORE_KEYS = literalsOf(IGNORE_LABELS)

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
    private val SEPARATOR_LINE = Regex("^([-=*_~#]{3,}|[—─━]{2,})$")

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
    fun parse(raw: String?): Result? = parseRaw(raw)
        ?.dropTitleGluedAccount()
        ?.dropDoubledLabel(raw)

    /**
     * 标题尾巴上多留了一个字段名：`计算机网站密码密码  Zx8Kq2Lm7V` 会把标题认成
     * 「计算机网站密码」。原文里同一个字段名连着写了两遍时，标题只该留前一半。
     */
    private fun Result.dropDoubledLabel(raw: String?): Result {
        val name = title ?: return this
        if (raw == null) return this
        val suffix = listOf("密码", "密碼", "口令", "账号", "帐号", "账户")
            .firstOrNull { name.endsWith(it) } ?: return this
        if (!raw.contains(suffix + suffix)) return this
        val trimmed = name.dropLast(suffix.length).trim()
        return if (trimmed.isEmpty()) this else copy(title = trimmed)
    }

    private fun parseRaw(raw: String?): Result? {
        if (raw.isNullOrBlank()) return null

        // 顺手去掉 BOM：从 Excel、CSV 里复制出来的文本开头常带一个，
        // 粘在第一个字段名前（`\uFEFF平台`）会让整个表头认不出列。
        val text = fullWidthLabel(raw).replace("\r\n", "\n").replace('\r', '\n').replace("\uFEFF", "")
            .let { unglueLabels(stripOuterQuotes(it)) }
            // `三、淘宝 tb_54 Tb@545454`：手抄笔记爱用中文序号打头，
            // 那个「三、」不是账号。只剥整段开头那一处。
            .replaceFirst(CN_RANK_PREFIX, "")
            .let { dropLeadIn(it) }
        var username: String? = null
        var password: String? = null
        var url: String? = null
        var title: String? = null
        var notes: String? = null

        // ── 第零步：结构化形状 ────────────────────────────────────
        // JSON 对象和表格行里字段名是显式的，交给下面按位置猜的规则，
        // 只会把引号、逗号当成值的一部分（`username="account":"x"` 那种鬼东西）。
        jsonRecord(text)?.takeIf { it.hasCredential }?.let { return it.named(text) }
        // XML、括号包值、行首带列名的一行一条，字段名同样是显式的。
        // 这三种形状交给"按位置猜"只会得到半截值：
        // `<user>wx</user>` 会被当成账号、`账号(xx)` 会连标签一起进用户名。
        xmlRecords(text)?.firstOrNull()?.takeIf { it.hasCredential }?.let { return it.named(text) }
        // 网页表格：`<tr><td>淘宝</td><td>ht_1</td><td>Ht@111111</td></tr>`。
        // 从浏览器复制表格时剪贴板会带 HTML，落到输入框里就是这种横行。
        htmlRecords(text)?.firstOrNull()?.takeIf { it.hasCredential }?.let { return it.named(text) }
        parenRecords(text)?.firstOrNull()?.takeIf { it.hasCredential }?.let { return it.named(text) }
        slashRecord(text)?.takeIf { it.hasCredential }?.let { return it.named(text) }
        // 查询串里成对出现的短键名（`?u=ac_08&p=Ab@080808`）。
        // 单看 `u`、`p` 什么都不是 —— `p` 也可能是页码 —— 所以必须两边都齐
        // 才认，缺一边就交回后面的规则。
        queryPair(text)?.let { (user, pass) ->
            val host = firstHostOutsideEmail(text, emptyList())
            return Result(username = user, password = pass, url = host).named(text)
        }
        // 命令行里的登录参数（`mysql -u db_1 -p Db@222222`）。
        // 这条要排在 headerlessRows / guessFromLeftovers 前面：
        // 否则 `-u` 会被当成账号、`db_1` 被当成密码，整体错一格。
        cliRecord(text)?.takeIf { it.hasCredential }?.let { return it.named(text) }
        // 一排横杠分隔的两段（`tb_13@example.com ---- Tb@131313`）。
        // 横杠既不是冒号也不是逗号，键值认不出、按位置猜只会把后一段当账号。
        if (!text.contains('\n')) {
            DASH_GAP.find(text.trim())?.let { m ->
                val head = m.groupValues[1].trim()
                val tail = m.groupValues[2].trim()
                if ((looksLikeAccountValue(head) || cleanUrl(head) != null) && looksLikeSecret(tail)) {
                    return Result(username = head, password = tail).named(text)
                }
            }
        }
        // 单行三到四格的表格（`淘宝|tb_5|Tb@555555|常用`）。没有表头，靠列数定位置。
        // 这条必须排在 labeledRows 前面：那一条要求行首就是列名，认不出裸值。
        if (!text.contains('\n')) {
            headerlessRows(listOf(text))?.singleOrNull()?.takeIf { it.hasCredential }?.let { return it.named(text) }
        }
        labeledRows(text)?.firstOrNull()?.takeIf { it.hasCredential }?.let { return it.named(text) }
        tableRecords(text).firstOrNull()?.takeIf { it.hasCredential }?.let { return it.named(text) }

        // 一行的 `标签:账号:密码`（`微信:demo_wx_001:Wx@123456`）。
        // 必须排在 key:value 前面 —— `邮箱:demo@x.com:Mail#2024` 里的「邮箱」
        // 本身就是字段名，先走 kv 会把整个 `demo@x.com:Mail#2024` 当成账号。
        val chainLines = text.split('\n')
            .map { it.stripLineIndex().edgeTrim() }
            .filter { it.isNotEmpty() }
        // 只是「有冒号」还不够：`账号：a1，密码：b1` 也能被切成三段，
        // 但字段名已经明写出来了，这时候 kv 比位置猜更可靠。
        // 判据是 kv 自己能不能凑出账号和密码两样：凑得出就别猜位置，
        // 只有 `邮箱:demo@x.com:Mail#2024` 这种 kv 只认出一个字段的才交给链式。
        val chainAllowed = !kvCoversBoth(text)
        if (chainAllowed && chainLines.size >= 2 && chainLines.all { splitColonChain(it) != null }) {
            parse(chainLines.first())?.let { return it }
        }
        if (chainAllowed) chainLines.singleOrNull()?.let { splitColonChain(it) }?.let { chain ->
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
            // 备注是"剩下的话"：`备注：绑定邮箱:x@y.com，验证码=手机` 里冒号和等号都是
            // 正文的一部分，截到下一个字段名会把备注砍成「绑定」。所以备注先取到行尾，
            // 只在"空格后面又冒出一个字段名"处收住（`备注：n9 网址：www.a9.com`）。
            val valueEnd = if (match.groups["notes"] != null) {
                lineEnd
            } else if (next != null && next.range.first < lineEnd) {
                next.range.first
            } else {
                lineEnd
            }
            val raw = text.substring(valueStart, valueEnd)
            // 值是因为撞上下一个字段名才截断的，尾巴上可能挂着下一组的平台名
            // （`淘宝 账号 tb_20 淘宝 密码 Tb@202020` → 账号会带上「淘宝」）。
            val stopsAtLabel = next != null && next.range.first < lineEnd
            // 备注是"剩下的话"，值取到行尾前先按「空格后又一个键」收住：
            // `备注：老账号 平台：微博 账号：wb_40 密码：Wb@404040` 只该留下「老账号」。
            val cleaned = if (match.groups["notes"] != null) notesTail(raw) else trimLabelStub(raw.cleanValue())
            val value = if (stopsAtLabel && match.groups["notes"] == null) trimSiteTail(cleaned) else cleaned
            val lineIndex = text.take(match.range.first).count { it == '\n' }

            when {
                match.groups["user"] != null && username == null -> {
                    username = value.ifBlank { null }
                }
                match.groups["pass"] != null && password == null -> {
                    val (secret, parenNote) = splitParenNote(value)
                    password = secret.ifBlank { null }
                    if (notes == null && parenNote != null) notes = parenNote
                    // 字段名左边还挂着账号：`【淘宝】ta_07密码：Ta@070707`。
                    if (username == null) username = accountBeforeLabel(text, match.range.first)
                }
                match.groups["url"] != null && url == null -> {
                    url = cleanUrl(value) ?: value.ifBlank { null }
                }
                match.groups["title"] != null && title == null -> {
                    // `网站：www.taobao.com`：标着标题、值却是网址，归到网址去
                    val host = cleanUrl(value)
                    if (host != null && url == null) url = host else title = value.ifBlank { null }
                }
                match.groups["notes"] != null && notes == null -> {
                    notes = value.ifBlank { null }
                }
            }
            consumedLines += lineIndex
            // 备注是"剩下的话"，值取到行尾；但同一行后面还写着别的键时
            // （`备注：老账号 平台：微博 账号：wb_40 密码：Wb@404040`），
            // 后面的键得接着认，否则整行只落了备注、一条记录都没有。
            // 备注里自带的 `绑定邮箱:x@y.com` 前面没有空格，不算键。
            val resumeAt = if (match.groups["notes"] != null) {
                KV_REGEX.findAll(raw)
                    .firstOrNull { m -> m.range.first > 0 && raw[m.range.first - 1].isWhitespace() }
                    ?.let { valueStart + it.range.first }
            } else {
                next?.takeIf { it.range.first < lineEnd }?.range?.first
            }
            searchFrom = if (match.groups["notes"] != null && resumeAt != null) {
                resumeAt
            } else if (valueEnd > valueStart) {
                valueEnd
            } else {
                valueStart
            }
        }

        // ── 第二步：key value（只有空白）────────────────────────────
        // 有冒号的写法上面已经吃掉，这里补"不打冒号"的：`用户名 admin`、`密码 abc123`。
        var bareFrom = 0
        while (true) {
            val match = BARE_KV_REGEX.find(text, bareFrom) ?: break
            val valueStart = match.range.last + 1
            val lineEnd = text.indexOf('\n', valueStart).let { if (it < 0) text.length else it }
            val next = BARE_KV_REGEX.find(text, valueStart)
            // 同一行里后面跟着冒号写法的键（`密码 p1 备注：验证码存在邮箱里`）时，
            // 值必须截止到那里，否则密码会变成 `p1 备注：验证码存在邮箱里`。
            val nextColon = KV_REGEX.find(text, valueStart)
            // 备注取到行尾（同第一步的理由：备注里的冒号、等号是正文）。
            val valueEnd = if (match.groups["notes"] != null) {
                lineEnd
            } else {
                listOfNotNull(next?.range?.first, nextColon?.range?.first)
                    .filter { it < lineEnd }
                    .minOrNull() ?: lineEnd
            }
            val raw = text.substring(valueStart, valueEnd)
            val stopsAtLabel = listOfNotNull(next?.range?.first, nextColon?.range?.first).any { it < lineEnd }
            val cleaned = if (match.groups["notes"] != null) notesTail(raw) else trimLabelStub(raw.cleanValue())
            val value = if (stopsAtLabel && match.groups["notes"] == null) trimSiteTail(cleaned) else cleaned
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
                match.groups["title"] != null && title == null -> {
                    val host = usable?.let { cleanUrl(it) }
                    if (host != null && url == null) url = host else title = usable
                }
                match.groups["notes"] != null && notes == null -> notes = usable
            }
            consumedLines += lineIndex
            if (valueLine != lineIndex) consumedLines += valueLine
            bareFrom = if (valueEnd > valueStart) valueEnd else valueStart
        }

        // 字段名夹在句子里时（`淘宝 账号：a13 密码：p13！`），整行都被 kv 吃掉，
        // 剩下的行里已经没有平台名，得回到「第一个字段名之前的那截」去拿。
        if (title == null) prefixTitle(text)?.let { title = it }

        // 账号就是一串邮箱、密码另写在旁边：`mail_19@example.com 密码 87654321`。
        // 不先认下来的话，第四步会把整段里的邮箱当站点收走，账号栏空着。
        if (username == null && password != null) {
            MAIL_ACCOUNT.find(text)?.value?.takeIf { it != password }?.let { username = it }
        }

        // ── 第三步：链接里的凭据 ─────────────────────────────────
        // 抓包/抓取工具导出的东西常写成 `https://user:pass@host`。
        // 这一步必须排在"整段里找网址"前面：否则整行先被当成网址吃掉，
        // 账号会变成 `Demo%40123@example.com/login` 这种残渣。
        // 逐行找而不是全段找 —— 一次粘好几条链接时，全段只能捞到第一条。
        if (username == null || password == null) {
            for (line in text.split('\n')) {
                val found = uriUserinfo(line) ?: continue
                if (username == null) username = found.first
                if (password == null) password = found.second
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
        var leftovers = text.split('\n')
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
            // 但只整行就是那个网址时才丢 —— `淘宝 taobao_70 Tb@707070 https://…`
            // 这种同一行里还有账号密码，丢整行会把这条记录丢掉。
            .filter { line -> !(url != null && cleanUrl(line) == url && line.none { it.isWhitespace() }) }

        // `Steam:` 单独占一行、账号密码在后面两行：这一行是平台名，不是「字段名=空值」。
        // 只认整行就是「一个词 + 冒号」的写法；`账号：` 这种字段名过不了站点名判定，
        // 留给下面按位置猜。
        if (title == null && leftovers.size >= 2) {
            val head = leftovers.first()
            val named = Regex("^([^\\s:：=＝]{1,16})\\s*[:：]$").matchEntire(head)
            val name = named?.groupValues?.get(1)
            if (name != null &&
                (isPlainSiteName(name) || isAsciiSiteName(name) || isMixedSiteName(name) ||
                    isDigitSiteName(name))
            ) {
                title = name
                leftovers = leftovers.drop(1)
            }
        }

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
                // 左侧那半截当标题：连着账号一起抠出来时（`微博账号weibo_13`），
                // 剩下的 `微博` 就是平台名；单纯「云盘账户」这种也照旧当标题，
                // 但要把尾巴上的「账号」「密码」剥掉，否则标题会变成「微博账号」。
                if (title == null) {
                    val head = stripChatter(mixed.head)
                    title = if (mixed.user != null) {
                        head.takeIf { isPlainSiteName(it) }
                    } else {
                        mixed.head
                    }
                }
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
            title = leftovers
                .map { it.filterEmoji() }
                // 已经当成账号或密码用掉的词不能再当标题：`admin\n13700001111`
                // 两行都是值，`admin` 是账号，不是站点名。
                .filter { it != username && it != password && it != url }
                // 「账号密码如下：」这种是引子，里面带着字段名，不是平台名。
                .filterNot { line -> LEAD_IN_WORDS.any { line.contains(it) } }
                .firstOrNull { isPlainSiteName(it) || isAsciiSiteName(it) }
        }
        // 转发卡片和截图常把平台名包在括号里：`「淘宝」账号 …`、`【淘宝】`。
        // 这一类没有"平台："这样的键，只剩括号能看出哪个词是平台名。
        if (title == null) {
            val bracket = BRACKET_TITLE.find(text)
            val inside = bracket?.groupValues?.get(1)?.trim()
                // `【淘宝：购物】` 里冒号后面是描述，平台名只到冒号前。
                ?.substringBefore('：')?.substringBefore(':')?.trim()
                // `【1】淘宝`：序号不是平台名，平台名在括号外，看括号后面那截。
                ?.replace(Regex("^[0-9]{1,4}[.、,，)）]*"), "")
                ?.trim()
            title = if (!inside.isNullOrEmpty()) {
                inside.takeIf { it.any { ch -> ch.code >= 0x2E80 } }
            } else {
                // 括号里只有序号（`【1】淘宝`）时，平台名紧跟在右括号后面。
                bracket?.let {
                    text.substring(it.range.last + 1)
                        .lineSequence()
                        .firstOrNull()
                        ?.trim()
                        ?.takeWhile { ch -> ch.code >= 0x2E80 }
                        ?.takeIf { name -> name.isNotEmpty() }
                }
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

        // `计算机网站密码密码  Zx8Kq2Lm7V`：按位置猜会把「平台名+密码」当成账号，
        // 于是多出一个「账号 = 平台名 密码」。这条判断放在 [parse] 外层统一做，
        // 因为第零步那些"直接 return"的分支不经过这里。
        val result = Result(
            username = username?.trim()?.ifBlank { null },
            password = password, // 密码不做任何 trim / 归一化，见类注释
            url = url?.trim()?.ifBlank { null },
            title = title?.trim()?.ifBlank { null }?.let { tidyTitle(it) },
            notes = notes?.trim()?.ifBlank { null }
        ).fixedOrder()
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

        // 顺手去掉 BOM：从 Excel、CSV 里复制出来的文本开头常带一个，
        // 粘在第一个字段名前（`\uFEFF平台`）会让整个表头认不出列。
        val text = fullWidthLabel(raw).replace("\r\n", "\n").replace('\r', '\n').replace("\uFEFF", "")
            .let { unglueLabels(stripOuterQuotes(it)) }
            .let { dropLeadIn(it) }

        // 0) 一行里用顿号把几组键值串起来：`账号 a、密码 b、账号 c、密码 d`。
        //    先把顿号换成换行再走一遍，胜过在单行里硬切。
        if (text.contains('、')) {
            val split = text.split('\n').joinToString("\n") { line ->
                val labels = LABEL_ANY.findAll(line).count()
                if (labels >= 2 && line.contains('、')) line.replace('、', '\n') else line
            }
            if (split != text) parseMany(split).takeIf { it.size >= 2 }?.let { return it }
        }

        // 0) JSON 数组 / XML / 括号包值 / 行首带列名 / 一整张表：        //    字段名是显式的，别再让"按位置猜"的规则去猜
        jsonRecords(text)?.let { if (it.size >= 2) return it }
        xmlRecords(text)?.let { if (it.size >= 2) return it }
        htmlRecords(text)?.let { if (it.size >= 2) return it }
        parenRecords(text)?.let { if (it.size >= 2) return it }
        labeledRows(text)?.let { if (it.size >= 2) return it }
        // 表格只抄了一行数据（表头 + 一行）也算表格：卡在 ≥2 会让整张表
        // 退到下面的按位置猜，表头被当成一条、数据行被切成半条。
        tableRecords(text).let { if (it.isNotEmpty()) return it }

        // 1) 分隔线分段：`---` 之类在记事本里就是"下一条"的意思
        val sections = splitBySeparator(text)
        if (sections.size >= 2) {
            val parsed = sections.mapNotNull { parse(it) }.filter { it.hasCredential }
            if (parsed.size >= 2) return parsed
        }

        val lines = text.split('\n')
            .map { it.stripLineIndex().edgeTrim() }
            .filter { it.isNotEmpty() && !isSeparatorLine(it) && !isHeaderLine(it) }

        // 0.4) 没有表头、但每行都是同样几格：`淘宝;l1;Tb@111111` / `京东;l2;Jd@222222`。
        //      这种表以前一条都认不对 —— 分号不在"行内分隔符"里，整行按连写处理，
        //      于是标题是 `淘宝`、密码是 `;l1;Tb@111111`。
        headerlessRows(lines)?.let { return it }

        // 0.45) 每行都是 `平台/账号/密码` 或 `平台|账号|密码` 三格：
        //       `淘宝/tb_1/Tb@111111`、`京东/jd_1/Jd@111111`。
        //       单行版在 parse 第零步；多行时不要求"每一行都成"，前面一句引言
        //       （`登录信息如下：`）不该让整段退回单条 —— 只要带分隔符的行全成即可。
        val slashed = lines.mapNotNull { line -> slashRecord(line)?.takeIf { it.hasCredential } }
        val slashedCandidates = lines.count { it.contains('/') || it.contains('|') || it.contains('｜') }
        if (slashed.size >= 2 && slashed.size == slashedCandidates) return slashed

        // 0.48) 同一行连着写两套键：`账号 a、密码 b、账号 c、密码 d`。
        repeatedBareKeys(text)?.let { return it }

        // 0.5) 账号、密码上下交替抄：`a@b.com` / `Mail@123` / `c@d.com` / `Mail#654`。        //      这种形状没有任何一行能自己拆开、也没有重复的键可切，
        //      全部规则都认不出，最后只拼出第一条、后两行整段丢掉。
        alternatingRecords(lines)?.let { return it }

        // 0.6) 每一行自己就是一条完整记录：`微博账号 wb_1 密码 Wb@111111` 连抄两行。
        //      这种行两端粘着字段名，上面的规则要么只认出最后一行、要么并成一条。
        //      逐行能各自认出账号和密码时，一行就是一条。
        if (lines.size >= 2) {
            val perLine = lines.mapNotNull { line -> parse(line)?.takeIf { it.hasCredential } }
            if (perLine.size == lines.size &&
                perLine.all { it.username != null && it.password != null }
            ) {
                return perLine
            }
        }

        // 0.65) 每行都是「平台名 + 密码 + 值」，既没有账号也没有任何分隔符：
        //       `微软账户密码Zx8Kq2Lm7V`、`Cloudflare密码 Zx8Kq2Lm7V!`。
        //       这种行单独 parse 都成，但整段粘一起时上面每条分支都要么要求
        //       "账号和密码都齐"、要么要求某一格分隔符，于是整段并成一条错的。
        if (lines.size >= 2 &&
            lines.none { line -> line.any { it == ':' || it == '：' || it == '=' || it == '＝' } }
        ) {
            val perLine = lines.mapNotNull { line -> parse(line)?.takeIf { it.password != null } }
            if (perLine.size == lines.size) return perLine
        }

        // 1) 一行一条的「连写」：`云盘账户密码Zx8Kq2Lm7V` 这种既没空格也没冒号，
        //    下面几条规则全都认不出（每行切不出 ≥2 段、没有空行、没有重复的 key），
        //    只会退化成一条错的 —— 标题丢了，账号变成整行，后面的行整段消失。
        if (lines.size >= 2 && lines.all { splitMixed(it) != null }) {
            val parsed = lines.mapNotNull { parse(it) }.filter { it.hasCredential }
            if (parsed.size >= 2) return parsed
        }

        // 带字段名的行（`Site: Steam`、`账号：a1`）：这种一段文字本来就是一整条记录，
        // 「一行一条」的各种拆法都会把标题、账号、密码拆成几条半记录，后面几处要靠它跳过。
        val looksKeyed = lines.any { KV_REGEX.containsMatchIn(it) || BARE_KV_REGEX.containsMatchIn(it) }

        // 2) 一行一条「标签:账号:密码」：`微信:demo_wx_001:Wx@123456`。
        //    三个冒号的形状以前一条都认不出 —— 整行被当账号、后面的行全丢。
        //    某一行自己就能被 kv 拆成账号+密码时不算（`账号：a1，密码：b1`）。
        if (lines.size >= 2 && lines.none { kvCoversBoth(it) } &&
            lines.all { splitColonChain(it) != null }
        ) {
            val parsed = lines.mapNotNull { parse(it) }.filter { it.hasCredential }
            if (parsed.size >= 2) return parsed
        }

        // 2) 一行一条：每行都能切出 ≥2 段
        //    带字段名（`Site: Steam`）的不算 —— 那种一段文字本来就是一整条记录，
        //    每行都切得开，按"一行一条"拆会把标题、账号、密码拆成三条半记录。
        if (!looksKeyed && lines.size >= 2 && lines.all { splitInline(it).size >= 2 }) {
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
    /**
     * 记录起点往回退：先退到行首，再退过「单独一行的平台名」。
     *
     * `京东\n账号 a2\n密码 p2` 里那条记录是从「京东」开始的，不是从「账号」开始的；
     * 把边界留在账号行，第二条记录就丢了标题。
     */
    private fun recordStartOf(text: String, index: Int): Int {
        val lineStart = text.lastIndexOf('\n', (index - 1).coerceAtLeast(0))
            .let { if (it < 0) 0 else it + 1 }
        if (lineStart <= 0) return lineStart
        val prevEnd = lineStart - 1
        val prevStart = text.lastIndexOf('\n', (prevEnd - 1).coerceAtLeast(0))
            .let { if (it < 0) 0 else it + 1 }
        val prev = text.substring(prevStart, prevEnd).trim()
        return if (prev.isNotEmpty() && columnOf(prev) == null && isPlainSiteName(prev)) prevStart else lineStart
    }

    private fun recordStarts(text: String): List<Int> {
        val starts = arrayListOf(0)
        var seenPassword = false
        // 只凭「密码之后又冒出个账号键」就切块会切错：`账号：ph1 密码：… 手机：138…`
        // 里的手机号会变成第二条。所以还要求它后面跟着自己的密码，
        // 或者它本身就是标题键（`平台：` 才是一段记录的开头）。
        val passPositions = KV_REGEX.findAll(text)
            .filter { it.groups["pass"] != null }
            .map { it.range.first }
            .toList()
        KV_REGEX.findAll(text).forEach { match ->
            if (match.groups["pass"] != null) {
                seenPassword = true
                return@forEach
            }
            val isRecordHead = match.groups["title"] != null || match.groups["user"] != null
            if (!isRecordHead || !seenPassword) return@forEach
            val opensOwnRecord = match.groups["title"] != null ||
                passPositions.any { it > match.range.first }
            if (!opensOwnRecord) return@forEach
            val lineStart = recordStartOf(text, match.range.first)
            val boundary = if (lineStart > starts.last()) lineStart else match.range.first
            if (boundary > starts.last()) starts.add(boundary)
            seenPassword = false
        }
        return starts
    }

    /**
     * 同一行里连着写了两套「账号 密码」（`账号 a、密码 b、账号 c、密码 d`）。
     *
     * 没有冒号，KV 认不到；BARE 那一遍会把四个值一路吃下去 ——
     * 第一条成了「标题=a 账号=b 密码=c 备注=d」。第二个账号键就是新记录的起点。
     */
    private fun repeatedBareKeys(text: String): List<Result>? {
        val users = BARE_KV_REGEX.findAll(text).filter { it.groups["user"] != null }.toList()
        val passes = BARE_KV_REGEX.findAll(text).count { it.groups["pass"] != null }
        if (users.size < 2 || passes < 2) return null
        val bounds = listOf(0) + users.drop(1).map { recordStartOf(text, it.range.first) } + listOf(text.length)
        val chunks = bounds.zipWithNext()
            .map { (from, to) -> text.substring(from, to).trim() }
            .filter { it.isNotEmpty() }
        if (chunks.size < 2) return null
        val parsed = chunks.map { parse(it)?.takeIf { r -> r.hasCredential } }
        if (parsed.any { it == null || it.password == null }) return null
        return parsed.filterNotNull()
    }

    /** 按整行横线切段；没有横线时返回整段本身。 */
    private fun splitBySeparator(text: String): List<String> {        val sections = arrayListOf<String>()
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
            // 「微博账号weibo_13密码Wb@131313」：账号夹在中间，后面又跟着中文
            // 「密码」，所以上面那条"整名只有 账号+值"的规则不匹配；这里直接从
            // 名字里把 `账号weibo_13` 抠出来，剩下的「微博」才是平台名。
            EMBEDDED_ACCOUNT.find(name)?.let { m ->
                val account = m.groupValues[1]
                val site = name.substring(0, m.range.first).trim()
                return MixedLine(site, tail, true, account)
            }
        }

        return MixedLine(name, tail, isPassword)
    }

    /**
     * 值尾巴上只剩字段名的前半截时切掉：`Rp@111111 确认`、`p1 重复`。
     *
     * `确认密码`、`重复密码` 都在字段名表里，但正则从值的位置往后找下一处字段名时，
     * 那个空格落在搜索起点之外、边界判定不成立，于是下一处字段名没被认出，
     * 值一路吃到行尾。与其在正则上绕，不如事后把「词的半截」剪掉：
     * 真密码不会以「空格 + 残缺字段名」收尾。
     */
    private fun trimLabelStub(raw: String): String {
        val v = raw.trim()
        val cut = v.lastIndexOf(' ')
        if (cut <= 0) return v
        val tail = v.substring(cut + 1)
        if (tail.isEmpty() || tail.length > 6) return v
        val stub = LABEL_WORDS.any { it != tail && it.startsWith(tail) }
        return if (stub) v.substring(0, cut).trim() else v
    }

    /**
     * 密码后面用括号补了一句人话：`密码：Br@454545 (备用)`。
     *
     * 括号里没有字母数字（"备用"、"临时"、"内部"）时就只是说明，不该留在密码里；
     * 有字母数字的（`Pa@ss(2024)`）可能真是密码的一部分，原样保留。
     */
    private val PAREN_NOTE = Regex("^(\\S+)\\s*[（(]([^（）()]{1,24})[)）]$")

    private fun splitParenNote(value: String): Pair<String, String?> {
        val m = PAREN_NOTE.matchEntire(value.trim()) ?: return value to null
        val note = m.groupValues[2].trim()
        if (note.isEmpty() || note.any { it.isLetterOrDigit() && it.code < 0x2E80 }) return value to null
        return m.groupValues[1] to note
    }

    /**
     * 值尾巴上挂着下一组的平台名：`淘宝 账号 tb_20 淘宝 密码 Tb@202020`。
     *
     * 平台名和字段名原先粘在一起（`淘宝账号 tb_20 淘宝密码 Tb@202020`），
     * 掰开成「平台名 + 空格 + 字段名」之后，第二处平台名没处去，
     * 被前一个值一路吃到自己的字段名前面。值确实在下一处字段名处截止过、
     * 末词又是纯中文平台名，就说明这个词属于下一组，剪掉。
     */
    private fun trimSiteTail(raw: String): String {
        var text = raw.trim()
        while (true) {
            val cut = text.indexOfLast { it.isWhitespace() }
            if (cut <= 0) return text
            val tail = text.substring(cut + 1)
            if (!isPlainSiteName(tail)) return text
            val head = text.substring(0, cut).trim()
            if (head.length < 2) return text
            text = head
        }
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
            val tokens = splitInline(lines[0]).filterNot { cleanUrl(it) != null }
            // `淘宝 admin1:Pa@ss123`：其中一段本身就是「账号:密码」，另一段是平台名。
            // 这种行按位置猜会把整段 `admin1:Pa@ss123` 当账号，密码留空。
            val pair = tokens.firstNotNullOfOrNull { token ->
                splitAccountColonPassword(token)?.let { token to it }
            }
            if (pair != null) {
                val site = tokens.firstOrNull { it != pair.first && isPlainSiteName(it) }
                return Triple(pair.second.first, pair.second.second, site)
            }
            // 一句话里的口头包装（`淘宝的账号是 taobao_12 密码是 Tb@121212`）：
            // 「淘宝的账号是」不是标题、「密码是」也不是密码，按位置猜会整体错位。
            // 剥掉包装后剩下的词才是值；其中像平台名的那半截当标题备选。
            // 注意留的是**剥完之后**的词：留着原串会让「淘宝的账号是」既占一个位置
            // 又当上标题（`parts.size >= 3` 那条分支直接把 parts[0] 当标题）。
            val parts = tokens.map { stripChatter(it) }.filter { it.isNotEmpty() }
            val hint = tokens.firstNotNullOfOrNull { token ->
                stripChatter(token).takeIf { isPlainSiteName(it) }
            }
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
                    } else if (isLabelSlot(head) && isLabelSlot(tail)) {
                        // `平台(必填) 账号(必填)`：两段都是「列名(占位说明)」，
                        // 这是表单的字段提示，不是账号密码。
                        Triple(null, null, null)
                    } else if (isPlainSiteName(head) && tail.any { it.isDigit() }) {
                        Triple(tail, null, head)
                    } else {
                        Triple(head, tail, null)
                    }
                }
                parts.isEmpty() -> Triple(null, null, null)
                // 孤零零一串六到八位数字（`123456`）：这是密码的概率远高于账号。
                // 账号要么带字母、要么是十一位手机号，很少有恰好六到八位的纯数字。
                parts.size == 1 && parts[0].matches(Regex("^[0-9]{6,8}$")) ->
                    Triple(null, parts[0], null)
                // 只剩一个词、而密码已经从别处认出来了（`淘宝` / `密码：Tb@161616`），
                // 那个词是平台名而不是账号。
                isPlainSiteName(parts[0]) && knownPass != null -> Triple(null, null, parts[0])
                else -> Triple(if (knownUser == null) parts[0].ifBlank { null } else null, null, null)
            }
            if (guess.third == null && hint != null) {
                return Triple(guess.first, guess.second, hint)
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
        line.split(INLINE_SEPARATORS)
            .map { it.trim() }
            // `网易云音乐 - dash_1 - Dash@111111` 里那个孤零零的 `-` 不是值。
            // 只滤"整段都是分隔符字符"的，`a-b` 这种带内容的照旧保留。
            .filter { it.isNotEmpty() && it.any { ch -> ch.isLetterOrDigit() } }

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

    /** 纯英文短词也常是平台名（`Gmail`、`Steam`），但不能带数字和点。 */
    private fun isAsciiSiteName(raw: String): Boolean {
        val text = raw.trim()
        return text.length in 2..20 && text.all { it.isLetter() && it.code < 0x2E80 }
    }

    /**
     * 纯数字也是平台名：`12306 账号 rail_19 密码 Rl@191919`。
     *
     * 铁路、航空、运营商这类平台名就是号码。位数卡在 2..8，
     * 免得把 `13700001111` 这种手机号当平台名（那是账号，有字段名时走账号）。
     */
    private fun isDigitSiteName(raw: String): Boolean {
        val text = raw.trim()
        return text.length in 2..8 && text.all { it.isDigit() }
    }

    /**
     * 中英混排的平台名：`QQ邮箱`、`B站`、`Steam中国`、`网易163`。
     *
     * 必须**两者都有**才算 —— 纯汉字的前面可能还挂着发信人名字
     * （`小李: 淘宝账号 tb_9`），那种要留给「取最后一段」的兜底，
     * 在这里放行会把「小李」当平台名。
     */
    private fun isMixedSiteName(raw: String): Boolean {
        val text = raw.trim()
        if (text.length !in 2..12) return false
        val hasHan = text.any { it.code in 0x4E00..0x9FA5 }
        val hasAscii = text.any { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }
        if (!hasHan || !hasAscii) return false
        return text.all {
            it.code in 0x4E00..0x9FA5 || it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' ||
                it in "（）()-·"
        }
    }

    /**
     * 平台名和字段名粘成一段时先掰开：`微博账号 wb_1` → `微博 账号 wb_1`。
     *
     * 中文老抄法里平台名后面直接跟字段名、中间不留空格，`账号` 的左边是汉字，
     * 字段名正则要求左边是空白或标点，于是这一整段没人认，账号就丢了
     * （`微博账号 wb_1 密码 Wb@111111` 只捞到一个密码）。
     *
     * 只在「汉字平台名 + 账号/密码类字段名 + 空白 + 值」这一种形状上动手，
     * 别的写法原样返回，免得把「请用账号登录」这类句子也拆了。
     */
    /**
     * 整段被一对引号包着（`"账号：q_1 密码：Q@111111"`）。
     *
     * 只剥最外层这一对，内部的值不动；不成对时原样返回，
     * `5'6"` 这种半截引号留在值里仍是原样。
     */
    private fun stripOuterQuotes(text: String): String {
        val trimmed = text.trim()
        if (trimmed.length < 2) return text
        val openers = "\"'“”‘’「」『』"
        if (trimmed.first() == trimmed.last() && openers.contains(trimmed.first())) {
            // 里面还有同一种引号时不能剥：`"淘宝","tb_72","Tb@727272"`
            // 是 CSV 的三格，剥掉最外层就把引号配对全打乱了。
            val inner = trimmed.substring(1, trimmed.length - 1)
            if (inner.contains(trimmed.first())) return text
            return inner
        }
        return text
    }

    /**
     * 全角字段名转半角：`Ｕｓｅｒ：fw_40` 是中文输入法打出来的键名，
     * 键值正则只认半角字母，整行认不出。
     *
     * 只动「冒号前面那一段」，冒号后面的值一个字符都不碰 ——
     * 有人密码本身就是全角（`Ｆｗ＠１１１１１１`），转半角等于把密码改错。
     */
    private fun fullWidthLabel(text: String): String {
        if (text.none { it.code in 0xFF21..0xFF3A || it.code in 0xFF41..0xFF5A }) return text
        return FULL_WIDTH_LABEL.replace(text) { m ->
            m.groupValues[1].map { ch ->
                val code = ch.code
                if (code in 0xFF21..0xFF3A || code in 0xFF41..0xFF5A) (code - 0xFEE0).toChar().lowercaseChar() else ch
            }.joinToString("")
        }
    }

    private fun unglueLabels(text: String): String {
        // 反引号是 Markdown 的行内代码标记，粘进来只会挂在值的两端
        // （`账号：bt_41` `密码：Bt@414141` 的账号会变成 "bt_41` `"）。
        val plain = text.replace("`", "")
        // 字段名中间被空格劈开（`账 号：sp_28`）：不并回去就会被当成
        // 「账号叫账、密码叫号：sp_28」的一条怪记录。
        val joined = SPACED_LABEL.replace(plain) { m -> m.groupValues[1] + m.groupValues[2] }
        // 英文键名中间夹空格（`Account Name:`、`Pass Word:`）：并成连写再走键值。
        val merged = EN_SPACED_LABEL.replace(joined) { m ->
            (m.groupValues[1] + m.groupValues[2]).lowercase()
        }
        // `账号是st_26`：口语里用「是/为」连接，补成冒号走键值。
        val linked = linkLabels(merged)
        // `淘宝：账号 tb_07`：冒号左边是平台名，不是字段名。
        // 不把冒号换成空格，左边那截会跟着账号一起被当值（`淘宝：` 进用户名框）。
        val tuned = SITE_COLON_LABEL.replace(linked) { m ->
            val head = m.groupValues[1]
            if (isPlainSiteName(head) || isAsciiSiteName(head) || isMixedSiteName(head)) "$head ${m.groupValues[2]}" else m.value
        }
        val quoted = BRACKETED_LABEL.replace(tuned) { m -> "${m.groupValues[1]}：" }
        if (!quoted.contains('\n') && !UNGLUED_ANY.containsMatchIn(quoted) && !UNGLUED_AFTER.containsMatchIn(quoted)) {
            return quoted
        }
        return quoted.split('\n').joinToString("\n") { line ->
            // 值是英文、字段名紧接着贴在后面（`账号=acc_23密码=Pw@1`）：
            // 先补一个空格，让字段名的左边界成立，值才不会被吃到下一个键。
            val spaced = UNGLUED_AFTER.replace(line) { m -> "${m.groupValues[1]} ${m.groupValues[2]}" }
            UNGLUED_ANY.replace(spaced) { m ->
                val head = m.groupValues[1]
                val label = m.groupValues[2]
                // `确认密码：` 这类本身就是一个字段名，拆开反而把「确认」当平台名、
                // 「密码」当字段名，值会跑到备注里。整串是字段名就原样留着。
                if (isLabelWord(head + label)) {
                    m.value
                } else {
                    "$head $label${m.groupValues[3]}"
                }
            }
        }
    }

    /**
     * 汉字平台名和字段名连在一起（`微博账号 wb_1`、`小李: 淘宝账号 tb_9`）。
     *
     * 字段名的左边界只认空白和标点，汉字紧贴着就整段不匹配，
     * 于是 `微博账号 wb_1` 里账号取不到、只剩密码。这里在汉字和字段名之间补一个空格，
     * 后面的键值解析照常走。**只在字段名后面跟空白时才拆**，`淘宝账号：wx_1` 不动。
     */
    private val UNGLUED_ANY = Regex(
        "([\\u4e00-\\u9fa5]{2,6})(账号|帐号|账户|用户名|登录名|密码|密碼|口令)(\\s+)(?=\\S)"
    )

    /**
     * 字段名被方头括号或书名号包着（`【账号】tb_21`、`「密码」Tb@212121`）。
     *
     * 括号把字段名和值隔开，键值正则的左边界和分隔符都对不上，
     * 整行会被当成「标题是【账号】」的一条记录。统一改写成 `账号：`。
     * **只认表里的字段名**，`【淘宝】` 这种平台名不受影响。
     */
    private val BRACKETED_LABEL = Regex(
        "[【\\[「『（(](账号|帐号|账户|用户名|登录名|密码|密碼|口令|平台|平台名|网站|网址|备注|说明)[】\\]」』）)]\\s*[:：=＝]?\\s*"
    )

    /**
     * 字段名中间夹一个空格（`账 号：`、`密 码：
     * ）：并回去再走键值。
     *
     * 后面必须紧跟冒号/等号才算字段名 —— 有人密码里就带空格
     * （`密码：我 的 密 码`），无条件并字会把密码改掉。
     */
    private val SPACED_LABEL = Regex("([账帐密口用登邮])\\s+([号码令户录箱])(?=\\s*[:：=＝])")

    /**
     * 口语里的连接词：`账号是st_26`、`密码为Pw@1`。
     *
     * 左边必须是边界：备注里「这个账号是给爸妈用的」是在说事情，
     * 不是字段名，改写成冒号会把备注切成一条假记录。
     */
    private val LABEL_LINK = Regex(
        "(账号|帐号|账户|用户名|登录名|登陆名|密码|密碼|口令)(?:是|为)[ \\t\\u3000]*(?=\\S)"
    )

    /**
     * `账号是 st_26`、`淘宝的账号是 taobao_12`：口语用「是/为」连接，补成冒号走键值。
     *
     * 左边界仍是硬要求 —— 备注里「这个账号是给爸妈用的」是在说事情，改写成
     * 冒号会切出一条假记录。但 `淘宝的账号是…` 里的「的」是平台名的结尾助词，
     * 它不该把后面的字段名挡在门外。
     */
    private fun linkLabels(text: String): String =
        LABEL_LINK.replace(text) { m ->
            val before = text.getOrNull(m.range.first - 1)
            val blocked = before != null && before != '的' && (before.isLetter() || before.isDigit())
            if (blocked) m.value else m.groupValues[1] + "："
        }

    /**
     * 字段名紧贴在英文值后面、中间一个空白都没有（`账号=acc_23密码=Pw@1`）。
     *
     * 字段名的左边界要求前面不是字母数字，`acc_23` 的 `3` 把 `密码` 挡在门外，
     * 于是账号会把 `密码=Pw@1` 一起吞下去。这里补一个空格让边界成立。
     */
    private val UNGLUED_AFTER = Regex(
        "([A-Za-z0-9_.@+\\-])(账号|帐号|账户|用户名|登录名|密码|密碼|口令|名称|平台|网址|备注|用户)" +
            "(?=[=＝:：\\s])"
    )

    /** 引号、书名号、方头括号包着的平台名，剥掉壳再判。 */
    private fun unwrapName(raw: String): String =
        raw.trim().trim('"', '\'', '“', '”', '‘', '’', '「', '」', '『', '』', '【', '】', '《', '》').trim()

    /**
     * 结构化形状（JSON、XML、表格、行首带列名）在第一步就返回了，
     * 走不到后面的「标题兜底」。这里补一次：`淘宝🛒\n账号 emo_1\n密码 …`
     * 这种第一行是平台名、但整体被 labeledRows 认走的情况，标题不能丢。
     */
    private fun Result.named(text: String): Result {
        if (title != null) return this
        val guess = prefixTitle(text)
            ?: text.split('\n')
                .asSequence()
                .map { it.edgeTrim().filterEmoji() }
                .firstOrNull { isPlainSiteName(it) || isAsciiSiteName(it) }
                ?.let { tidyTitle(it) }
        return if (guess == null) this else copy(title = guess)
    }

    /**
     * 标题最后过一遍：剥序号残渣和圆括号壳。
     *
     * 按位置猜出来的标题不会走 [`prefixTitle`]，`【1】淘宝` 的左括号被
     * 当成标点剪掉后只剩 `1】淘宝`，`(淘宝)` 连壳一起进了标题框。
     */
    private fun tidyTitle(raw: String): String? {
        // 只剥序号残渣和圆括号壳，**不碰表情**：`平台：抖音🎵` 里的表情
        // 是用户自己写进值里的，属于平台名的一部分。
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val ranked = RANK_TAIL.replace(trimmed, "").trim()
        val bare = shellName(ranked) ?: ranked
        return bare.ifBlank { null }
    }

    /**
     * 开头的「账号密码如下：」这类引子行删掉。
     *
     * 它既不是键值也不是平台名，留着会被当成标题（`账号密码如下`），
     * 真正的平台名（下一行那个「淘宝」）反而落空。
     */
    private fun dropLeadIn(text: String): String {
        val lines = text.split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty() || LEAD_IN_LINE.matches(line)) {
                if (line.isEmpty()) { i++; continue }
                i++
                continue
            }
            break
        }
        return if (i == 0) text else lines.drop(i).joinToString("\n")
    }

    /**
     * 第一个字段名之前的那截，当平台名用。
     *
     * 只在这种时候有用：整行都被 kv 吃掉（`淘宝 账号：a13 密码：p13！`），
     * 剩下的行里已经找不到平台名了。`example.com 账号 a14` 这种截出来是域名，
     * 域名归 url 字段，不抢标题。
     */
    private fun prefixTitle(text: String): String? {
        for (line in text.split('\n')) {
            if (line.isBlank()) continue
            // 「账号密码如下：」这种是引子，不是平台名。整行以冒号收尾就跳过，
            // 否则它会被当成标题，真正的平台名（下一行那个）反而落空。
            if (line.trimEnd().lastOrNull() in listOf(':', '：')) continue
            // 只看**第一条带字段名的行**。往后扫没意义：
            // `初始密码：Tb@525252` 的第二行会截出「初始」当标题。
            val cut = (KV_REGEX.find(line) ?: BARE_KV_REGEX.find(line)) ?: continue
            if (cut.range.first <= 0) return null
            val head = unwrapName(line.substring(0, cut.range.first))
                .filterEmoji()
                // `淘宝 | 账号：vi_1`：分隔符跟着平台名一起被截进来，剥掉再判。
                // 全角括号同理：`（淘宝 账号：br_1 …）` 的左括号紧贴在平台名前面。
                .trim(
                    ' ', '\t', '|', '｜', ',', '，', ';', '；', '-', '—', '·', '、', ':', '：', '/',
                    '（', '(', '【', '[', '「', '『', '《'
                )
            if (head.isEmpty()) return null
            // 带协议或片段的行（`http://localhost:8080/admin#login 账号：…`）：
            // 前面的网址归 url，标题留空，别把路径末段 `login` 当平台名。
            if (head.contains("://") || head.contains('#')) return null
            // 日志行前面顶着日期时间（`2026-11-02 22:01 淘宝 账号 …`）、
            // 平台名后面跟着路由器地址（`路由器 192.168.1.1 账号 …`）：
            // 各自剪掉，剩下的才是平台名。整段丢掉会让标题空掉。
            val name = TIME_PREFIX.replace(URLISH_TOKEN.replace(head, " "), " ").trim(
                ' ', '\t', '|', '｜', ',', '，', ';', '；', '-', '—', '·', '、', ':', '：', '/'
            )
            if (name.isEmpty()) return null
            // `【1】淘宝 tb_08 …`：左括号被上面当成标点剥掉了，只剩 `1】淘宝`。
            // 序号是排版的，不是平台名的一部分。
            val numbered = RANK_TAIL.replace(name, "").trim()
            // `(淘宝) tb_14 …`、`【淘宝】 tb_51 …`：平台名被括号包着，剥壳才是真名。
            val shelled = shellName(numbered) ?: numbered
            if (shelled.isEmpty()) return null
            if (shelled.lowercase() in NOISE_HEADS) return null
            // `淘宝的账号：taobao_12`：平台名后面还跟着一个「的」，不是平台名的一部分，
            // 先剪掉再判，否则「淘宝的」也会被当成平台名收下。
            val plain = shelled.trimEnd('的', '得', '地', '了', '里', '上')
            val cand = if (plain != shelled && plain.isNotEmpty() && isPlainSiteName(plain)) plain else shelled
            if (isPlainSiteName(cand) || isAsciiSiteName(cand) || isBracketedName(cand) ||
                isDigitSiteName(cand) || isMixedSiteName(cand)
            ) {
                return cand
            }
            // `淘宝 App 6.0 账号：tb_06`：平台名后面跟着客户端名和版本号，
            // 整段不像平台名，但开头那截汉字就是。只认后面紧跟版本号的写法，
            // 否则 `账号 a1 密码 p1` 里的 `账号` 会被当成平台名。
            val lead = Regex("^([\\u4e00-\\u9fa5]{2,8})\\s+(?:[Aa]pp|APP|客户端|v?\\d)")
                .find(shelled)?.groupValues?.get(1)
            if (lead != null && isPlainSiteName(lead) && !isLabelWord(lead)) return lead
            // `小李: 淘宝账号 tb_9`：前面还挂着发信人的名字，取最后一段。
            val tail = shelled.split(Regex("[^\\u4e00-\\u9fa5A-Za-z0-9]+"))
                .lastOrNull { it.isNotBlank() } ?: return null
            return tail.takeIf { isPlainSiteName(it) || isAsciiSiteName(it) }
        }
        return null
    }

    /** 全角字母打在冒号前面的那一段（`Ｕｓｅｒ：`）：只把它换成半角小写。 */
    private val FULL_WIDTH_LABEL = Regex("([\\uFF21-\\uFF3A\\uFF41-\\uFF5A]{2,20})(?=\\s*[:：=＝])")

    /** `三、淘宝 …`：中文数字打头的序号。 */
    private val CN_RANK_PREFIX = Regex("^[一二三四五六七八九十]{1,3}\\s*[、.．]\\s*")

    /** 带着这些词的整行是说明/引子（`账号密码如下：`），不能当平台名。 */
    private val LEAD_IN_WORDS = listOf("账号", "帐号", "账户", "密码", "口令", "如下", "以下")

    /** `账号密码如下：`、`登录信息如下` 这类引子整行。它既不是键值也不是平台名。 */
    private val LEAD_IN_LINE = Regex("^(.{0,16})(如下|以下|下列|如上|如下面)\\s*[:：]?\\s*$")

    /** 一行里出现几个字段名（判断`账号 a、密码 b、账号 c、密码 d`这类连写）。 */
    private val LABEL_ANY =
        Regex("(账号|帐号|账户|用户名|登录名|密码|密碼|口令|平台|备注)\\s*[:：=＝]?")

    /** `1】淘宝` 里被剥剩的序号尾巴（左括号已被当标点剪掉）。 */    private val RANK_TAIL = Regex("^[0-9]{1,4}\\s*[】\\]」』]\\s*")

    /** `(淘宝)`、`（淘宝）`：平台名外面套的圆括号。 */
    private val PAREN_SHELL = Regex("^[（(]\\s*([^（）()]{1,20}?)\\s*[）)]$")

    /** `【淘宝】`、`[淘宝]`、`「淘宝」`、`《淘宝》`：平台名外面套的方头括号与书名号。 */
    private val BRACKET_SHELL = Regex("^[【\\[「『《]\\s*([^】\\]」』》]{1,20}?)\\s*[】\\]」』》]$")

    /** 把平台名外面的壳剥掉（圆括号、方头括号、书名号都算），没壳就返回 null。 */
    private fun shellName(raw: String): String? =
        PAREN_SHELL.matchEntire(raw)?.groupValues?.get(1)?.trim()
            ?: BRACKET_SHELL.matchEntire(raw)?.groupValues?.get(1)?.trim()

    /** 日志行里的日期时间前缀，出现就说明这段是日志不是平台名。 */
    /**
     * 邮箱形状的账号。`mail_19@example.com 密码 87654321` 里的邮箱既是账号
     * 也是"站点"的唯一线索，第四步会把裸域名当网址收走，所以先按账号认。
     */
    private val MAIL_ACCOUNT = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")

    private val TIME_PREFIX = Regex("\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}|\\d{1,2}:\\d{2}(?::\\d{2})?")

    /**
     * 平台名前面挂着的地址：IP、域名、带协议的网址。
     *
     * `路由器 192.168.1.1 账号：admin 密码：…` 里前半截是登录页地址，
     * 该进 url 列；标题应当是「路由器」。整段当网址丢掉会把真标题一起丢了。
     */
    private val URLISH_TOKEN = Regex(
        "\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?" +
            "|(?:[A-Za-z][A-Za-z0-9+.\\-]*://)?(?:[A-Za-z0-9][A-Za-z0-9\\-]*\\.)+[A-Za-z]{2,}(?::\\d{1,5})?" +
            "(?:/[A-Za-z0-9._\\-/%#?=&]*)?" +
            "|localhost(?::\\d{1,5})?"
    )

    /**
     * 英文键名中间夹空格：`Account Name:`、`User ID:`、`Pass Word:`。
     *
     * 英文后台导出、日志里这么写很常见，而字段名表里存的是连写的
     * `accountname`/`userid`/`password`，不并起来就只认到后半截（`name`）。
     */
    private val EN_SPACED_LABEL = Regex("(?i)\\b(account|user|login|pass|email)\\s+(name|id|word)\\b(?=\\s*[:：=＝])")

    /**
     * 平台名后面先跟一个冒号，再跟字段名（`淘宝：账号 tb_07`）。
     *
     * 那个冒号是人写笔记时的分隔，不是「淘宝=账号」的赋值；当成键值的话
     * 平台名连同冒号会被塞进用户名框。
     */
    private val SITE_COLON_LABEL = Regex(
        "([^\\s:：=＝]{1,16})\\s*[:：][ \\t\\u3000]*(账号|帐号|账户|用户名|登录名|登录账户|密码|密碼|口令)(?=[\\s:：=＝]|$)"
    )

    /** `tb_13@example.com ---- Tb@131313`：一排横杠当分隔。 */
    private val DASH_GAP = Regex("^(.+?)\\s+[-—=*_~]{2,}\\s+(\\S+)$")

    /** 英文里高频出现、但绝不会是平台名的词：日志字段名一类。 */
    private val NOISE_HEADS = setOf("login", "logon", "log", "user", "pass", "password", "account", "name")

    /** 标题里带表情（`淘宝🛒`）：表情不是平台名的一部分，剥掉再判。 */
    private fun String.filterEmoji(): String =
        filter {
            // Kotlin 的 Char 是 UTF-16 单元，astral 平面（🛒 这类）在 String 里
            // 是两个代理项，各自都 < 0x1F000，只按码点范围过滤会把它们留下。
            it.code !in 0xD800..0xDFFF && it.code < 0x1F000 && it.code !in 0x2600..0x27BF
        }.trim()

    /** 带括号的平台名（`淘宝（中国）`、`京东(JD)`）。 */
    private fun isBracketedName(raw: String): Boolean {
        val text = raw.trim()
        if (text.length !in 2..12) return false
        return text.all { ch ->
            ch.code in 0x4E00..0x9FA5 || ch in "（）()【】《》·-"
        } && text.any { it.code in 0x4E00..0x9FA5 }
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
     * 值两端的收尾：空白、全角空格、不换行空格，以及粘进来的逗号分号竖线和 `&`。
     *
     * `&` 是查询串的分隔符：`password=Wb@123456&note=参数测试` 里不剥掉的话，
     * 密码会变成 `Wb@123456&`（用户反馈的原话是「`&` 这个符号是别进去了」）。
     *
     * 只动两端，中间的字符一个不碰 —— 密码里带空格是合法的。
     */
    private fun String.cleanValue(): String {
        var v = trim().trim('\u3000', '\u00A0')
        // 引号包住的值（`密码："P@ss word"`）去掉外壳，里面的空格留着。
        // 只在外壳成对时动，`5'6"` 这种半截引号当密码的不受影响。
        // 笔记软件里抄出来的值常带 Markdown 记号：`密码：\`Md@111111\``、`密码：**Bd@111111**`。
        // 先剥连续星号与反引号，再按引号/括号成对去壳。密码本身以星号开头的极少，
        // 但 `**` 这种成对加粗记号几乎只出现在 Markdown 里。
        while (v.length >= 2 && v.startsWith("**") && v.endsWith("**")) v = v.substring(2, v.length - 2).trim()
        if (v.length >= 2 && v.startsWith('*') && v.endsWith('*')) v = v.substring(1, v.length - 1).trim()
        // 句子里的值常写成 `账号是 "acme_01"，密码是 …`：值截到下一个字段名时
        // 尾巴上还粘着一个逗号，引号就不成对了，剥壳条件落空。先把这个标点去掉
        // 再看引号，只看「去掉之后正好露出引号」的情形，密码自己以逗号结尾不动。
        if (v.length >= 3) {
            val noTail = v.trimEnd('，', ',', '；', ';', '。', '、')
            if (noTail.length >= 2 && noTail.length < v.length && noTail.last() in "\"'“”‘’「」`") {
                v = noTail
            }
        }
        if (v.length >= 2) {
            val head = v.first()
            val tail = v.last()
            val paired = (head == '"' && tail == '"') || (head == '\'' && tail == '\'') ||
                (head == '“' && tail == '”') || (head == '‘' && tail == '’') ||
                (head == '「' && tail == '」') || (head == '`' && tail == '`')
            if (paired) v = v.substring(1, v.length - 1).trim()
        }
        // 成对的方括号把整段值包起来（`密码 [Tb@555555]`、`账号 【tb_55】`）：
        // 里面没有同种括号才是壳，剥掉；`[a][b]` 这种多段的保持原样。
        if (v.length >= 3) {
            val inner = v.substring(1, v.length - 1)
            val shelled = (v.first() == '[' && v.last() == ']') ||
                (v.first() == '【' && v.last() == '】') ||
                (v.first() == '《' && v.last() == '》')
            if (shelled && inner.isNotBlank() &&
                inner.none { it == v.first() || it == v.last() }
            ) {
                v = inner.trim()
            }
        }
        // 结构闭合符：从 JSON / XML 里连壳一起抄过来时，值后面会拖着 `}`、`]`、`)`。
        // 只在"多出来的闭合符"上剥：`adm_1（管理员）`、`（内部使用）` 的括号是内容，
        // 剥掉会把说明啃掉一半；`"pass":"y"}` 这种左边没开括号的才剥。
        while (v.isNotEmpty()) {
            val last = v.last()
            val opener = when (last) {
                '}' -> '{'
                ']' -> '['
                ')' -> '('
                '）' -> '（'
                '>' -> '<'
                '»' -> '«'
                else -> null
            } ?: break
            if (v.count { it == last } <= v.count { it == opener }) break
            v = v.dropLast(1).trim()
        }
        // 反过来：值尾巴上挂着一个没配对的左括号（`密码：Nt@373737（备注：备用）`
        // 里密码在第 6 步就停在 `（` 前面，备注另走一路）。留着就成了密码的一部分。
        while (v.isNotEmpty()) {
            val last = v.last()
            val closer = when (last) {
                '{' -> '}'
                '[' -> ']'
                '(' -> ')'
                '（' -> '）'
                '<' -> '>'
                else -> null
            } ?: break
            if (v.count { it == last } <= v.count { it == closer }) break
            v = v.dropLast(1).trim()
        }
        // 查询串里的第一个参数：`...?user=taobao_60&remember=1` 里值取到第一个 `&` 为止。
        // 只有当 `&` 后面还是一个 `键=值` 时才切，`p@ss&word` 这种真密码不动。
        val amp = v.indexOf('&')
        if (amp > 0 && v.substring(amp + 1).contains('=')) v = v.substring(0, amp).trim()
        // 空白后面拖一个分隔符：`账号：s1_1 / 密码：…` 的第一格会取到 `s1_1 /`。
        // 只认「空白 + 分隔符」在末尾，真密码不会以空格加斜杠收尾。
        v = v.replace(Regex("[\\s\u3000]+[/\\\\|｜]\\s*$"), "")
        v = v.trim(',', '，', ';', '；', '|', '&', '。', '、', '！', '？', '…')
        // 值后面还粘着下一个字段名：`账号：a1，密码：b1` 里第一格会取到 `a1，密码`。
        // 只剥「分隔符 + 字段名」这种粘法 —— 光看结尾会误伤真值，
        // 备注写着「老账号」时不该被当成字段名砍成「老」。
        repeat(2) {
            val stripped = v.replace(GLUED_LABEL, "").trim(
                ',', '，', ';', '；', '|', '&', '。', '、', '-', '—', '·', '/', '\\'
            )
            if (stripped.isNotEmpty() && stripped != v) v = stripped
        }
        return v.trim(',', '，', ';', '；', '|', '&', '。', '、', '！', '？', '…')
    }

    /** 同上，但更保守：只去空白类字符，不去标点。 */
    private fun String.edgeTrim(): String = trim().trim('\u3000', '\u00A0')

    /**
     * 备注的值：大体取到行尾，但"空格后面又冒出一个字段名"时收住。
     *
     * `备注：绑定邮箱:x@y.com，验证码=手机` 里的冒号、等号都是正文，不能切；
     * `备注：n9 网址：www.a9.com` 里 `网址：` 是下一个字段，跟着空格出现，
     * 归备注就成了「n9 网址：www.a9.com」，那串网址也一起丢了。
     */
    private fun notesTail(raw: String): String {        val text = raw.trim()
        val cut = KV_REGEX.findAll(text)
            .firstOrNull { m -> m.range.first > 0 && text[m.range.first - 1].isWhitespace() }
        val head = if (cut != null) text.substring(0, cut.range.first) else text
        return head.cleanValue()
    }

    /** 行首序号剥掉：`1. 淘宝 …`、`②淘宝 …`、`第3条 淘宝 …`；顺手剥项目符号。 */
    private fun String.stripLineIndex(): String {
        var rest = this
        LINE_INDEX_PREFIX.find(rest)?.let { m ->
            val after = rest.substring(m.range.last + 1)
            if (after.isNotBlank()) rest = after
        }
        // 手抄笔记爱用中文序号：`三、淘宝 tb_54 …`。没写成阿拉伯数字，
        // 上面那条规则认不到，`三` 会留在行首被当成账号。
        rest = rest.replaceFirst(Regex("^[一二三四五六七八九十]{1,3}\\s*[、.．]\\s*"), "")
        return rest.replaceFirst(BULLET_PREFIX, "")
    }

    /** 整行只有横线：`---`、`===`、`***`、`———`，也包括 Markdown 表格的 `| --- | --- |`。 */
    private fun isSeparatorLine(line: String): Boolean {
        val core = line.filterNot { it.isWhitespace() || it == '|' || it == '｜' }
        return core.isNotEmpty() && SEPARATOR_LINE.matches(core)
    }

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

    /**
     * 剥掉句子里的口头包装：`淘宝的账号是` → `淘宝`、`密码是` → 空串。
     *
     * 手抄和聊天记录里最常见的写法是「某平台的账号是 xxx，密码是 yyy」。
     * 以前按位置切词，`淘宝的账号是` 会占住标题的位置、`密码是` 会占住密码的位置，
     * 结果是标题一长串、密码写着「密码是」（用户反馈「识别得不对」）。
     *
     * 先剥尾部的「的/是/为」（可以连着出现：`是的`），再剥尾部的字段名。
     * 剩下的若还是字段名或空串，这个词就不是值，不能占位置。
     */
    private fun stripChatter(token: String): String {
        // 括号不剥：`平台(必填)` 要整串留给 `isLabelSlot` 认成「列名 + 占位说明」，
        // 剥掉半边括号会变成既不是列名也不是值的怪东西，接着就被当成账号存下去。
        var t = token.trim()
            .trim('「', '」', '『', '』', '【', '】', '《', '》', ':', '：', ',', '，', '。')
        while (true) {
            val next = t.removeSuffix("的").removeSuffix("是").removeSuffix("为").trim()
            if (next == t) break
            t = next
        }
        t = t.replace(LABEL_TAIL, "").trim()
        // 剥完又露出新的「的」（`淘宝的账号的`），再收一次
        while (true) {
            val next = t.removeSuffix("的").removeSuffix("是").removeSuffix("为").trim()
            if (next == t) break
            t = next
        }
        return t
    }

    /** 字段名清单，`LABEL_TAIL` 与「值里粘着字段名」两条规则共用一份。 */
    private val LABEL_WORDS = listOf(
        "登录账号", "登录密码", "支付密码", "确认密码", "重复密码", "用户账号", "用户名",
        "手机号码", "手机号", "邮箱地址", "账号", "帐号", "账户", "帳號", "密码", "密碼",
        "口令", "平台", "网站", "站点", "邮箱", "备注", "说明"
    )

    /** 一段话末尾的字段名：`淘宝的账号` 里的「账号」。 */
    private val LABEL_TAIL = Regex(
        "(" + LABEL_WORDS.joinToString("|") + ")$",
        RegexOption.IGNORE_CASE
    )

    /**
     * 值里粘着的下一个字段名：`账号：a1，密码：b1` 里第一格取到的是 `a1，密码`。
     * 没有空格当分隔时，字段名会跟着逗号一起挤进前一个值里。
     */
    private val GLUED_LABEL = Regex(
        "[,，;；|]\\s*(?:" + LABEL_WORDS.joinToString("|") + ")\\s*[:：=＝]?\\s*$",
        RegexOption.IGNORE_CASE
    )

    // ── XML / 括号包值 / 行首列名 / 交替抄写 ────────────────────

    /** `<![CDATA[...]]>` 包住的值：导出工具怕转义出错时会这么写，壳要剥掉。 */
    private fun unwrapCdata(raw: String): String {
        val text = raw.trim()
        if (!text.startsWith("<![CDATA[") || !text.endsWith("]]>")) return text
        return text.removePrefix("<![CDATA[").removeSuffix("]]>").trim()
    }

    /**
     * HTML 表格：`<tr><td>淘宝</td><td>ht_1</td><td>Ht@111111</td></tr>`。
     *
     * 网页上复制表格时剪贴板常常同时带 HTML 片段，落到输入框里就是这种一行。
     * 和 `tableRecords` 一样只认「格数固定且带账号密码列」的形状，格数按
     * 2→账号密码、3→标题账号密码、4→标题账号密码备注 定位置。
     */
    private fun htmlRecords(text: String): List<Result>? {
        if (!text.contains("<tr", ignoreCase = true)) return null
        val rows = HTML_ROW_REGEX.findAll(text).map { row ->
            HTML_CELL_REGEX.findAll(row.groupValues[1]).map { it.groupValues[1].trim() }.toList()
        }.filter { it.size in 2..4 }.toList()
        if (rows.isEmpty()) return null
        val width = rows.first().size
        if (rows.any { it.size != width }) return null
        val records = rows.mapNotNull { cells ->
            resultOfPairs(
                cells.mapIndexed { index, cell ->
                    val column = when {
                        width == 2 && index == 0 -> Column.USER
                        width == 2 && index == 1 -> Column.PASS
                        width == 3 && index == 0 -> Column.TITLE
                        width == 3 && index == 1 -> Column.USER
                        width == 3 && index == 2 -> Column.PASS
                        width == 4 && index == 0 -> Column.TITLE
                        width == 4 && index == 1 -> Column.USER
                        width == 4 && index == 2 -> Column.PASS
                        else -> Column.NOTES
                    }
                    column.name.lowercase() to cell
                }
            )
        }
        return records.takeIf { it.isNotEmpty() }
    }

    /**
     * `<account><platform>微信</platform><user>wx</user><pass>Wx@123</pass></account>`
     *
     * 账号导出工具常给这种浅层 XML。切块的锚点取**第一个字段名**：同名字段再次
     * 出现就是下一条记录的开头 —— 每条记录的字段是同一套，这比按 `</account>`
     * 切省事，也不依赖容器标签叫什么。
     */
    private fun xmlRecords(text: String): List<Result>? {
        if (!text.contains('<') || !text.contains("</")) {
            // 只有 `<entry site="…"/>` 这种自闭合标签时没有 `</`，也还有救
            return xmlAttributeRecords(text)
        }
        // `<![CDATA[Ca@111111]]>`：尖括号在值里面，成对标签的正则匹配不到，
        // 先把壳去掉再配对。CDATA 只是转义写法，剥了不影响值本身。
        val flat = text.replace("<![CDATA[", "").replace("]]>", "")
        val pairs = XML_PAIR_REGEX.findAll(flat)
            .map { it.groupValues[1].lowercase() to it.groupValues[2].trim() }
            .toList()
        if (pairs.size < 2) return xmlAttributeRecords(text)

        val anchor = pairs.first().first
        val chunks = arrayListOf<MutableList<Pair<String, String>>>()
        var current = arrayListOf<Pair<String, String>>()
        for (pair in pairs) {
            if (pair.first == anchor && current.isNotEmpty()) {
                chunks.add(current)
                current = arrayListOf()
            }
            current.add(pair)
        }
        if (current.isNotEmpty()) chunks.add(current)

        val records = chunks.mapNotNull { chunk -> resultOfPairs(chunk) }
        return records.takeIf { it.isNotEmpty() } ?: xmlAttributeRecords(text)
    }

    /**
     * 属性写法的 XML：`<entry site="淘宝" user="j1" pass="J#111111"/>`。
     *
     * 一个带属性的标签就是一条记录；外层容器（`<accounts count="2">`）只有
     * 一个属性、认不出账号密码，会被 [resultOfPairs] 的 `hasCredential` 挡掉。
     */
    private fun xmlAttributeRecords(text: String): List<Result>? {
        if (!text.contains('=')) return null
        val records = XML_TAG_REGEX.findAll(text).mapNotNull { tag ->
            val attrs = XML_ATTR_REGEX.findAll(tag.groupValues[2])
                .map { it.groupValues[1] to it.groupValues[2].ifBlank { it.groupValues[3] } }
                .toList()
            if (attrs.size < 2) null else resultOfPairs(attrs)
        }.toList()
        return records.takeIf { it.isNotEmpty() }
    }

    /** 一串「字段名 → 值」组装成一条记录。字段名一个都认不出时返回 null。 */
    private fun resultOfPairs(pairs: List<Pair<String, String>>): Result? {
        var username: String? = null
        var password: String? = null
        var url: String? = null
        var title: String? = null
        var notes: String? = null
        var recognized = false
        for ((key, raw) in pairs) {
            val value = raw.trim().ifBlank { null } ?: continue
            if (isLabelWord(value)) continue
            when (columnOf(key)) {
                Column.USER -> if (username == null) {
                    username = value
                    recognized = true
                }
                Column.PASS -> if (password == null) {
                    password = value
                    recognized = true
                }
                Column.TITLE -> if (title == null) {
                    title = value
                    recognized = true
                }
                Column.URL -> if (url == null) {
                    url = cleanUrl(value) ?: value
                    recognized = true
                }
                Column.NOTES -> if (notes == null) {
                    notes = value
                    recognized = true
                }
                Column.IGNORE, null -> Unit
            }
        }
        if (!recognized) return null
        // `<name>x_1</name><pwd>X@111111</pwd>` 这种：`name` 落在平台名字段里，
        // 可一条记录既有密码又只有这一个值，它就是账号。光看「是不是英文」
        // 不够 —— `Steam` 也是英文；带数字/下划线/@/点 的才当账号。
        if (username == null && password != null && title != null && looksLikeAccountValue(title)) {
            username = title
            title = null
        }
        return Result(
            username = username,
            password = password,
            url = url,
            title = title,
            notes = notes
        ).takeIf { it.hasCredential }
    }

    /** 值本身长得像账号：能过 `ACCOUNT_TOKEN`，且带数字、下划线、@ 或点。 */
    private fun looksLikeAccountValue(raw: String): Boolean {
        val text = raw.trim()
        if (!ACCOUNT_TOKEN.matches(text)) return false
        return text.any { it.isDigit() || it == '_' || it == '@' || it == '.' }
    }

    /**
     * `平台(淘宝) 账号(taobao_bracket) 密码(Tb@123456)` —— 值被括号包起来。
     *
     * 早先括号被当成值的一部分：标题是「平台(淘宝)」、账号是「账号(taobao_bracket)」，
     * 用户看到的原话是「识别的标签识别成『平台(京东）』了」。
     */
    private fun parenRecords(text: String): List<Result>? {
        val records = text.split('\n').mapNotNull { parenRecord(it.stripLineIndex().edgeTrim()) }
        return records.takeIf { it.isNotEmpty() }
    }

    /**
     * 斜杠隔开的三格：`淘宝/taobao_01/Tb@123456`。
     *
     * 只认一整行、不带空白、也不带 `://` 的 —— 带协议头的斜杠是网址，
     * 拆开只会把链接切碎。第一格必须是中文平台名，第二格像账号、第三格像密码。
     */
    private fun slashRecord(text: String): Result? {
        val line = text.trim()
        if (line.isEmpty()) return null
        if (line.contains("://")) return null
        // 竖线（半角与全角）也是常见的分格符：`淘宝｜taobao_35｜Tb@353535`
        val raw = line.split('/', '|', '｜').map { it.trim() }
        // 两格时还可能是「平台：账号 / 密码」：`淘宝：taobao_62 / Tb@626262`
        val cells = if (raw.size == 2 && raw[0].count { it == ':' || it == '：' } == 1) {
            val cut = raw[0].indexOfFirst { it == ':' || it == '：' }
            listOf(raw[0].substring(0, cut).trim(), raw[0].substring(cut + 1).trim(), raw[1])
        } else {
            raw
        }
        if (cells.size != 3 || cells.any { it.isEmpty() }) return null
        // 格子里不能带空白：`淘宝 taobao_01 Tb@123456` 这种空格分列的形状不归这里管。
        if (cells.any { cell -> cell.any { it.isWhitespace() } }) return null
        // 平台名也可以写在最后：`admin_18 / Pa@ss1818 / 淘宝`。
        // 第一格不是平台名、第三格是，就按「账号 / 密码 / 平台」理解。
        if (!isPlainSiteName(cells[0]) && isPlainSiteName(cells[2])) {
            return Result(username = cells[0], password = cells[1], title = cells[2])
                .takeIf { ACCOUNT_TOKEN.matches(cells[0]) && looksLikeSecret(cells[1]) }
        }
        if (!isPlainSiteName(cells[0])) return null
        if (!ACCOUNT_TOKEN.matches(cells[1])) return null
        if (!looksLikeSecret(cells[2])) return null
        return Result(username = cells[1], password = cells[2], title = cells[0])
    }

    /**
     * 命令行里的登录参数：`mysql -u db_1 -p Db@222222`。
     *
     * 这种行的账号密码前面挂着 `-u`/`-p` 开关，按位置猜会把开关本身当值。
     * 只认明确的开关名（含长写法与 `-p密码` 连写），并且要求第一个词是
     * 纯英文字母的命令名 —— 否则 `-p 8080` 这种端口参数会被当密码存进去。
     */
    private fun cliRecord(text: String): Result? {
        val line = text.trim()
        if (line.contains('\n')) return null
        val tokens = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size < 3) return null
        val command = tokens.first()
        if (!command.all { it.isLetter() && it.code < 0x2E80 }) return null
        var user: String? = null
        var pass: String? = null
        var index = 1
        while (index < tokens.size) {
            val token = tokens[index]
            val next = tokens.getOrNull(index + 1)
            when {
                token == "-u" || token == "--user" || token == "-U" -> {
                    user = next?.cleanValue()
                    index += 2
                }
                token == "-p" || token == "--password" || token == "-P" -> {
                    pass = next?.cleanValue()?.takeIf { !it.matches(PORT_ONLY) }
                    index += 2
                }
                token.startsWith("--user=") -> { user = token.substringAfter('=').cleanValue(); index++ }
                token.startsWith("--password=") -> {
                    pass = token.substringAfter('=').cleanValue()
                    index++
                }
                token.startsWith("-p") && token.length > 2 -> { pass = token.substring(2).cleanValue(); index++ }
                else -> index++
            }
        }
        if (user.isNullOrBlank() && pass.isNullOrBlank()) return null
        return Result(username = user?.ifBlank { null }, password = pass?.ifBlank { null }, title = command)
    }

    /** 端口、进程号这类四位以内的纯数字：`-p 8080` 不是密码。 */
    private val PORT_ONLY = Regex("^[0-9]{1,5}$")

    private fun parenRecord(line: String): Result? {        if (line.isEmpty()) return null
        val matches = PAREN_PAIR_REGEX.findAll(line).toList()
        if (matches.size < 2) return null
        val pairs = matches.mapNotNull { match ->
            val label = match.groupValues[1]
            val value = match.groupValues[2].trim()
            // 括号里写的是填表说明（`账号（必填）：ac_16`），不是值。
            // 当成值会得到一条「账号叫必填、密码叫必填」的假记录。
            if (value.isEmpty() || value in FORM_HINTS) null
            else if (columnOf(label) == null) null
            else label to value
        }
        if (pairs.size < 2) return null
        // 没有密码就不算凭据：`平台(必填) 账号(必填)` 这种表格式说明也会长成这样，
        // 认下去只会得到一条"账号叫『必填』"的假记录。
        val result = resultOfPairs(pairs) ?: return null
        return result.takeIf { it.password != null }
    }

    /**
     * 一行自成一条、行首写着列名：`平台|知乎|zhihu_mix|Zh#654321|竖线`。
     *
     * 同一段里每行可以用不同的分隔符（分号、竖线、逗号混着来），所以不能当表格 ——
     * 表格要求全段同一个分隔符 + 单独一行表头。行首那个列名就是「从这里开始按
     * [ROW_ORDER] 的顺序填空」的起点。
     */
    private fun labeledRow(line: String): Result? {
        if (line.isEmpty() || isSeparatorLine(line) || isHeaderLine(line)) return null
        var cells: List<String> = emptyList()
        for (delimiter in ROW_DELIMITERS) {
            val candidate = splitCells(line, delimiter)
            if (candidate.size > cells.size) cells = candidate
        }
        if (cells.size < 3) return null

        var username: String? = null
        var password: String? = null
        var url: String? = null
        var title: String? = null
        var notes: String? = null
        var cursor: Column? = null
        var filled = 0

        cells.forEachIndexed { index, rawCell ->
            val cell = rawCell.trim()
            if (cell.isEmpty()) return@forEachIndexed

            // 单元格自带列名：`平台=微博`、`密码:abc`
            val inline = splitCellLabel(cell)
            if (inline != null) {
                val column = columnOf(inline.first) ?: return@forEachIndexed
                when (column) {
                    Column.USER -> if (username == null) username = inline.second
                    Column.PASS -> if (password == null) password = inline.second
                    Column.TITLE -> if (title == null) title = inline.second
                    Column.URL -> if (url == null) url = cleanUrl(inline.second) ?: inline.second
                    Column.NOTES -> if (notes == null) notes = inline.second
                    Column.IGNORE -> Unit
                }
                cursor = nextColumn(column)
                filled += 1
                return@forEachIndexed
            }

            val head = columnOf(cell)
            if (index == 0) {
                // 行首不是列名，就不是这种形状
                if (head == null) return null
                cursor = head
                filled += 1
                return@forEachIndexed
            }
            if (head != null) {
                // 行中间又冒出一个列名：从它开始重新数
                cursor = head
                return@forEachIndexed
            }
            val column = cursor ?: return null
            when (column) {
                Column.USER -> if (username == null) username = cell
                Column.PASS -> if (password == null) password = cell
                Column.TITLE -> if (title == null) title = cell
                Column.URL -> if (url == null) url = cleanUrl(cell) ?: cell
                Column.NOTES -> if (notes == null) notes = cell
                Column.IGNORE -> Unit
            }
            cursor = nextColumn(column)
            filled += 1
        }

        if (filled < 2) return null
        return Result(
            username = username,
            password = password,
            url = url,
            title = title,
            notes = notes
        ).takeIf { it.hasCredential }
    }

    private fun labeledRows(text: String): List<Result>? {
        val all = text.split('\n')
            .map { it.stripLineIndex().edgeTrim() }
            .filter { it.isNotEmpty() }
        val rows = all.mapNotNull { labeledRow(it) }
        // 有一行不是「列名 + 值」时不能在这里收工：那种形状是「平台名单独一行 +
        // 下面几行键值」，把平台名行当噪声丢掉，标题就没了。
        return rows.takeIf { it.isNotEmpty() && it.size == all.size }
    }

    private fun nextColumn(after: Column): Column? {
        val index = ROW_ORDER.indexOf(after)
        return if (index < 0 || index + 1 >= ROW_ORDER.size) null else ROW_ORDER[index + 1]
    }

    /** 一个单元格本身就是「列名 = 值」。列名认不出时返回 null（那它只是普通的值）。 */
    private fun splitCellLabel(cell: String): Pair<String, String>? {
        val index = cell.indexOfFirst { it in "=＝:：" }
        if (index <= 0) return null
        val label = cell.substring(0, index).trim()
        val value = cell.substring(index + 1).trim()
        if (label.isEmpty() || value.isEmpty()) return null
        if (columnOf(label) == null) return null
        return label to value
    }

    /**
     * 账号一行、密码一行，交替往下抄：`demo@x.com` / `Mail@123456` / `demo2@x.com` / `Mail#654`。
     *
     * 奇数位（第 1、3、5…行）必须像账号，偶数位必须像密码，两组都满足才认。
     * 只认 4 行以上的偶数行数 —— 两行的「账号 + 密码」老规则已经认得出，
     * 这里再加一道只会抢走别的形状。
     */
    private fun alternatingRecords(lines: List<String>): List<Result>? {
        if (lines.isEmpty()) return null
        // 三行版本：头一行是平台名，后两行是一组（`邮箱` / `m1@example.com` / `Ma@111111`）。
        // 平台名是英文站名（`Gmail`）也算，只要不带数字和符号。
        val asciiTitle = lines[0].length in 2..20 && lines[0].all { it.isLetter() && it.code < 0x2E80 }
        if (lines.size == 3 &&
            (isPlainSiteName(lines[0]) || asciiTitle) &&
            looksLikeAccount(lines[1]) &&
            looksLikeSecret(lines[2])
        ) {
            return listOf(Result(username = lines[1], password = lines[2], title = lines[0]))
        }
        if (lines.size < 4 || lines.size % 2 != 0) return null
        val records = arrayListOf<Result>()
        var index = 0
        while (index < lines.size) {
            val account = lines[index]
            val secret = lines[index + 1]
            if (!looksLikeAccount(account) || !looksLikeSecret(secret)) return null
            records.add(Result(username = account, password = secret))
            index += 2
        }
        return records.takeIf { it.isNotEmpty() }
    }

    /** 像账号：邮箱、纯数字（手机号/QQ 号），或一段没有空白的英文数字串。 */
    private fun looksLikeAccount(line: String): Boolean {
        if (line.isEmpty() || line.any { it.isWhitespace() }) return false
        if (line.any { it.code >= 0x2E80 }) return false
        return ACCOUNT_TOKEN.matches(line)
    }

    /** 像密码：不带中文、不写成邮箱、长度够、字母数字都有。 */
    private fun looksLikeSecret(line: String): Boolean {
        if (line.isEmpty() || line.any { it.isWhitespace() }) return false
        if (line.any { it.code >= 0x2E80 }) return false
        if (EMAIL_SHAPE.matches(line)) return false
        return line.length >= 6 && line.any { it.isLetter() } && line.any { it.isDigit() }
    }

    /**
     * 同一行里字段名左边还挂着一个值：`【淘宝】ta_07密码：Ta@070707`。
     *
     * 左边那截先去掉方头括号包着的平台名，剩下最后一个词如果长得像账号
     * （带数字、下划线、@ 或点）就是这一条记录的账号 —— 只是没写字段名。
     * 左边带冒号等号的一律不动：那是上一个字段的值，会被自己的规则认走。
     */
    private fun accountBeforeLabel(text: String, labelStart: Int): String? {
        val lineStart = text.lastIndexOf('\n', labelStart).let { if (it < 0) 0 else it + 1 }
        if (lineStart >= labelStart) return null
        val head = BRACKET_TITLE.replace(text.substring(lineStart, labelStart), " ")
            .replace(Regex("[（(][^）)]{0,20}[）)]"), " ")
        if (head.any { it == '：' || it == ':' || it == '＝' || it == '=' }) return null
        val token = head.split(' ', '\t', '\u3000').lastOrNull { it.isNotBlank() } ?: return null
        val value = token.cleanValue()
        // 平台名和字段名粘在一起时（`计算机网站密码密码  Zx8Kq2Lm7V`），左边那截只是
        // 标题的尾巴 —— 这一行本来就没有账号，硬塞一个进去会多出一条错记录。
        if (value.endsWith("密码") || value.endsWith("密碼") || value.endsWith("口令")) return null
        return value.takeIf { looksLikeAccountValue(it) }
    }

    /**
     * `密码是 Ab@060606，账号是 ac_06`：两边都认出来了，值却各占错一格 ——
     * 账号那侧填的是密码。只有「一边明显像密码、另一边明显不像」时才换，
     * 两边都像密码（`abc123` / `abc456`）时保持原样，让人自己对调。
     */
    private fun Result.fixedOrder(): Result {
        val user = username ?: return this
        val pass = password ?: return this
        if (!looksLikeSecret(user) || looksLikeSecret(pass)) return this
        if (user.length < 6 || !looksLikeAccountValue(pass)) return this
        return copy(username = pass, password = user)
    }

    /** 一整格以字段名收尾（`计算机网站密码密码`），说明那是标题而不是账号。 */
    private val GLUED_LABEL_TAIL = Regex("(?:密码|密碼|口令|账号|帐号|账户)$")

    /**
     * 账号栏里只剩「标题后面再接一个字段名」的残渣时，把它清掉。
     *
     * `计算机网站密码密码  Zx8Kq2Lm7V` 按位置猜会得到「账号 = 计算机网站密码 密码」——
     * 那种行本来就没有账号，留着会让用户看到一条假账号。
     */
    private fun Result.dropTitleGluedAccount(): Result {
        val user = username ?: return this
        val name = title ?: return this
        val tail = user.removePrefix(name)
        if (tail == user || !GLUED_TITLE_TAIL.matches(tail)) return this
        return copy(username = null)
    }

    /** 账号栏里只剩「再接一个字段名」的残渣时用它判定（见 [dropTitleGluedAccount]）。 */
    private val GLUED_TITLE_TAIL = Regex("^[\\s]*(?:密码|密碼|口令|账号|帐号|账户)+[\\s]*(?:密码|密碼|口令)?$")

    /** 查询串里的一段 `键=值`。 */
    private val QUERY_PARAM = Regex("(?i)[?&]([a-z账号密口令]{1,12})=([^&\\s#]{1,120})")

    /** 查询串里算账号的键名。 */
    private val QUERY_USER_KEYS = setOf(
        "u", "uid", "user", "username", "usernm", "account", "acct", "login", "loginname",
        "email", "mail", "userid", "账号", "帐号", "用户名", "邮箱"
    )

    /** 查询串里算密码的键名。 */
    private val QUERY_PASS_KEYS = setOf(
        "p", "pw", "pwd", "pass", "password", "passwd", "secret", "pwd1", "密码", "密碼", "口令"
    )

    /**
     * 查询串里成对写着账号密码：`?u=ac_08&p=Ab@080808`。
     *
     * 短键名（`u`、`p`）单看没法用 —— `p` 也可能是页码 —— 所以要求
     * **两个键分别落在账号侧和密码侧**才认，缺一边就交回别的规则。
     */
    private fun queryPair(text: String): Pair<String, String>? {
        var user: String? = null
        var pass: String? = null
        for (match in QUERY_PARAM.findAll(text)) {
            val key = match.groupValues[1].lowercase()
            val value = percentDecode(match.groupValues[2]).trim()
            if (value.isEmpty()) continue
            if (user == null && key in QUERY_USER_KEYS) user = value
            else if (pass == null && key in QUERY_PASS_KEYS) pass = value
        }
        if (user == null || pass == null) return null
        return user to pass
    }

    // ── 表格与 JSON ─────────────────────────────────────────────

    /**
     * 没有表头、但每行都是同样几格：`淘宝;l1;Tb@111111` / `京东;l2;Jd@222222`。
     *
     * 分号、竖线不在「行内分隔符」里，这种两行三格的抄法以前整行被当成连写：
     * 标题是 `淘宝`、密码变成 `;l1;Tb@111111`。这里按格数补位置 ——
     * 两格当「账号 密码」、三格当「平台 账号 密码」、四格再加一列备注。
     *
     * 只在**每一行都切出同样多的格**时认，且要求每行都既有账号又有密码：
     * 混着别的形状（一格的行、格子自带字段名的行）时交回后面的规则。
     */
    /** 格子开头自带一个字段名时剥掉它：`账号 taobao_63` → `taobao_63`。 */
    private fun stripCellLabel(cell: String): String {
        val text = cell.trim()
        val head = text.split(' ').first().trim()
        if (head.isEmpty() || head == text) return text
        val column = columnOf(head) ?: return text
        if (column == Column.IGNORE) return text
        return text.substring(head.length).trim().ifEmpty { text }
    }

    private fun headerlessRows(lines: List<String>): List<Result>? {
        // 单行也认：`淘宝|tb_5|Tb@555555|常用` 就是一行四格，没有表头。
        if (lines.isEmpty()) return null

        fun assemble(rows: List<List<String>>): List<Result>? {
            val size = rows.first().size
            if (size < 2 || size > 4) return null
            if (rows.any { it.size != size }) return null
            // 格子里自带字段名（`平台=微博`）时那是 labeledRows 的形状，别抢
            if (rows.any { cells -> cells.any { splitCellLabel(it) != null } }) return null
            // 第一格以「密码／口令」收尾时（`计算机网站密码密码  Zx8Kq2Lm7V!!`），
            // 那是「平台名 + 密码 + 值」，不是「账号 密码」两列：
            // 按两列切会凭空造出一个账号，标题也丢。
            if (rows.all { row -> GLUED_LABEL_TAIL.containsMatchIn(row.first().trim()) }) return null
            // 平台名写在最后（`tb_16\tTb@161616\t淘宝\t常用`）：格数照旧，
            // 但位置整体错开一格，按 标题/账号/密码 排会把平台名当成密码。
            val siteLast = size >= 3 && rows.all { row ->
                val site = row[2].trim()
                (isPlainSiteName(site) || isAsciiSiteName(site) || isMixedSiteName(site)) &&
                    looksLikeAccountValue(row[0].trim()) && looksLikeSecret(row[1].trim())
            }
            val order = when {
                siteLast && size == 3 -> listOf(Column.USER, Column.PASS, Column.TITLE)
                siteLast -> listOf(Column.USER, Column.PASS, Column.TITLE, Column.NOTES)
                size == 2 -> listOf(Column.USER, Column.PASS)
                size == 3 -> listOf(Column.TITLE, Column.USER, Column.PASS)
                else -> listOf(Column.TITLE, Column.USER, Column.PASS, Column.NOTES)
            }
            val records = rows.mapNotNull { cells ->
                var user: String? = null
                var pass: String? = null
                var title: String? = null
                var notes: String? = null
                var url: String? = null
                order.forEachIndexed { index, column ->
                    val cell = cells.getOrNull(index)?.ifBlank { null } ?: return@forEachIndexed
                    // 格子自己带个标签（`账号 taobao_63 | 密码 Tb@636363`）时先剥掉，
                    // 否则用户名会变成「账号 taobao_63」。
                    val value = stripCellLabel(cell)
                    when (column) {
                        Column.USER -> if (user == null) user = value
                        Column.PASS -> if (pass == null) pass = value
                        Column.TITLE -> if (title == null) title = value
                        // 第四格可能是备注也可能是网址：`淘宝|tb_5|Tb@555555|常用`
                        // 与 `淘宝|c1|Tb@444444|taobao.com` 只差最后一格长什么样。
                        Column.NOTES -> if (notes == null && url == null) {
                            val host = cleanUrl(value)
                            if (host != null) url = host else notes = value
                        }
                        Column.URL -> if (url == null) url = cleanUrl(value) ?: value
                        Column.IGNORE -> Unit
                    }
                }
                Result(username = user, password = pass, url = url, title = title, notes = notes)
                    .takeIf { it.hasCredential }
            }
            if (records.size != lines.size) return null
            if (records.any { it.password == null }) return null
            // 密码那一格得像个密码：至少有一个半角字母或数字。
            // `项目,说明` / `淘宝,购物用` 这种两列的说明表会切出同样多的格，
            // 光看格数分不出来，靠这一条挡住。
            if (records.any { r -> r.password?.none { it.code < 128 && it.isLetterOrDigit() } != false }) return null
            return records
        }

        for (delimiter in TABLE_DELIMITERS) {
            assemble(lines.map { splitCells(it, delimiter) })?.let { return it }
        }
        // 斜杠分列（`淘宝/tb_86/Tb@868686`）：表头 `平台/账号/密码` 之后跟这种行。
        // 只在每格都不空、且格内没有字段名和尖括号时认 ——
        // 网址与 XML/HTML 里的斜杠、尖括号会切出空格子或标签碎片，正好被挡在外面。
        run {
            val rows = lines.map { line -> line.trim().split('/').map { it.trim() } }
            val clean = rows.all { row ->
                row.size in 2..4 && row.none { cell -> cell.isEmpty() || cell.any { it in "<>：:＝=" } }
            }
            if (clean) assemble(rows)?.let { return it }
        }
        // 等宽对齐的两列：表头 `用户名        密码` 下面跟一行 `wang_43       Wg@434343`。
        // 从记事本、终端、聊天记录里抄出来的表都是这样，列之间两个以上空格。
        // 单个空格不算 —— `卡号：6222 0212 3456 7890` 那串空格属于账号本身；
        // 格里带冒号等号的是 `淘宝：taobao_62 密码：Tb@626262` 那种写法，
        // 字段名在格子里，交给带字段名的规则，别在这里按位置猜。
        run {
            val rows = lines.map { line -> line.trim().split(Regex("[ \\u3000]{2,}|\\t")).map { it.trim() } }
            val clean = rows.all { row ->
                row.size in 2..4 && row.none { cell -> cell.isEmpty() || cell.any { it in "：:＝=" } }
            }
            if (clean) assemble(rows)?.let { return it }
        }
        return null
    }

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
     * `平台(必填)`、`账号（选填）`：列名后面挂一段占位说明，本身不是值。
     * 表单说明、网页截图里的字段提示常是这个形状，不能当账号密码收。
     */
    private fun isLabelSlot(text: String): Boolean {
        val m = PAREN_PAIR_REGEX.matchEntire(text.trim()) ?: return false
        val column = columnOf(m.groupValues[1])
        return column != null && column != Column.IGNORE
    }


    /**
     * 一整张表：第一行是表头，后面每行一条记录。
     *
     * CSV 与制表符表格是同一种东西，只是分隔符不同，所以统一按"挑分隔符"
     * 处理。**必须认出账号列和密码列**才算表格 —— 否则 `a,b` 这种两列文本
     * 也会被当成表，把本来能按位置猜出来的东西拆坏。
     */
    private fun tableRecords(text: String): List<Result> {
        // 这里不剥行首序号：`序号,平台,账号,密码` 那张表的每行都以 `1,` 开头，
        // 剥掉之后整行左移一列（平台变成序号、账号变成平台），全表错位。
        val lines = text.split('\n')
            .map { it.edgeTrim() }
            .filter { it.isNotEmpty() && !isSeparatorLine(it) }
        if (lines.size < 2) return emptyList()

        val delimiter = pickDelimiter(lines) ?: return emptyList()
        val header = splitCells(lines[0], delimiter)
        if (header.size < 2) return emptyList()

        val columns = header.map { columnOf(it) }
        if (Column.USER !in columns || Column.PASS !in columns) return emptyList()

        return lines.drop(1).mapNotNull { line ->
            var cells = splitCells(line, delimiter)
            // 表头有编号列时，编号本身就是那两三个数字，不是错位
            if (cells.size == columns.size + 1 && cells[0].matches(Regex("^\\d{1,4}$"))) {
                cells = cells.drop(1)
            }
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
    /**
     * 切表格的一行。引号里的分隔符不算分隔符 ——
     * `"a,1","p,1"` 是两格，不是四格；引号本身也一并剥掉。
     */
    private fun splitCells(line: String, delimiter: Char): List<String> {
        val cells = mutableListOf<String>()
        val buffer = StringBuilder()
        var quote: Char? = null
        for (ch in line) {
            when {
                quote != null -> if (ch == quote) quote = null else buffer.append(ch)
                ch == '"' || ch == '\'' -> quote = ch
                ch == delimiter -> {
                    cells += buffer.toString().trim()
                    buffer.clear()
                }
                else -> buffer.append(ch)
            }
        }
        cells += buffer.toString().trim()
        return cells
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
                // （`steam:demo:pass`）。带数字的英文串多半是账号，不当平台名。
                val asciiSite = head.length in 2..20 && head.all { it.isLetter() && it.code < 0x2E80 }
                // `tb_19:Tb@191919:淘宝`：平台名写在最后一段，头一段才是账号。
                // 只在这种「头是账号、中间是密码、尾巴像平台名」时才换位，
                // 否则 `淘宝:tb_19:Tb@191919` 会被反着理解。
                if (parts.size == 3) {
                    val last = parts[2]
                    val siteLast = isPlainSiteName(last) || isAsciiSiteName(last) || isMixedSiteName(last)
                    if (siteLast && looksLikeAccountValue(head) && looksLikeSecret(user)) {
                        return Triple(last, head, user)
                    }
                }
                if (!isPlainSiteName(head) && !asciiSite) return null
                head
            }
            else -> return null
        }
        return Triple(title, user, pass)
    }

    // ── 网址 ────────────────────────────────────────────────────

    /** 第一个"不在邮箱里"的裸域名。邮箱两侧的 host 段都要跳过。 */
    /**
     * 从 `协议://账号:密码@主机/路径` 里取账号密码。
     *
     * 不用 [`URL_USERINFO_REGEX`] 的原因：那个正则的密码段不许出现 `@`，
     * 而 `postgres://demo_user:Db@111111@db.example.com/app` 的密码里就有 `@`，
     * 正则会在这里截断、把 `Db` 当密码。改成先取 `@` 之前的整段 userinfo
     * （取**最后一个** `@`），再在第一个冒号处切开，密码里的 `@` 就留在密码里。
     * 账号为空（`redis://:Red@111111@host`）也认，只返回密码。
     */
    private fun uriUserinfo(line: String): Pair<String?, String?>? {
        val trimmed = line.trim()
        val scheme = URI_SCHEME.find(trimmed) ?: return null
        val rest = trimmed.substring(scheme.range.last + 1)
        // 只截到路径为止：密码里带 `#`（`ftp://demo:Ftp#2024@example.com/`）时，
        // 先按 `#` 截会把 `@` 一起剪掉，整条链接退化成按位置猜、密码被啃成
        // `Ftp#2024@example.com`。`?` 同理，交给后面的 `@` 定位。
        val authority = rest.substringBefore('/')
        val at = authority.lastIndexOf('@')
        if (at <= 0) return null
        val userinfo = authority.substring(0, at)
        val user = userinfo.substringBefore(':', "").let { percentDecode(it) }.ifBlank { null }
        val pass = if (userinfo.contains(':')) {
            userinfo.substringAfter(':').let { percentDecode(it) }.ifBlank { null }
        } else {
            null
        }
        if (user == null && pass == null) return null
        return user to pass
    }

    private val URI_SCHEME = Regex("^[a-z][a-z0-9+.\\-]*://", RegexOption.IGNORE_CASE)

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

    /**
     * 字段名标注本身够不够用：`账号：a1，密码：b1` 是够的，
     * `邮箱:demo@x.com:Mail#2024` 只认出一个字段就不够，要靠链式或连写补。
     */
    private fun kvCoversBoth(text: String): Boolean {
        var hasUser = false
        var hasPass = false
        KV_REGEX.findAll(text).forEach { match ->
            if (match.groups["user"] != null) hasUser = true
            if (match.groups["pass"] != null) hasPass = true
        }
        return hasUser && hasPass
    }
}
