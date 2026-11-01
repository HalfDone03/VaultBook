package com.yg.vaultbook.autofill

/** 一个可编辑字段被判定的角色。 */
enum class FieldKind {
    USERNAME,
    PASSWORD,
    /** 可编辑但与登录无关（搜索框、验证码、备注…），不要拿它参与填充或捕获 */
    IGNORE,
    UNKNOWN
}

/**
 * 系统给我们的关于某个字段的全部线索。
 *
 * 刻意用原始数值 `inputType` 而不是 `android.text.InputType` 常量：
 * 这样整套判定逻辑是**纯 Kotlin**，能在 JVM 单测里覆盖各种真实站点的怪异写法，
 * 不需要真机也不需要 Robolectric。
 */
data class FieldSignals(
    /** `ViewNode.idEntry`，通常是 `android:id/...` 或 WebView 里的 `id` */
    val idEntry: String? = null,
    /** `ViewNode.hint` */
    val hint: String? = null,
    /** `ViewNode.autofillHints`，网站声明了语义时最可靠 */
    val autofillHints: List<String> = emptyList(),
    /** `ViewNode.htmlInfo.attributes`，仅 WebView 有 */
    val htmlAttributes: Map<String, String> = emptyMap(),
    /** `ViewNode.inputType` 原值 */
    val inputType: Int = 0,
    val isEditable: Boolean = true
)

/**
 * 登录表单字段识别。
 *
 * ── 为什么要这么啰嗦 ────────────────────────────────────────
 * Android 的 Autofill 只在网站**声明了** `autofillHints` 时才完全可靠，
 * 而现实里大量站点什么都没声明；同时 Flutter/React Native 自绘的登录页
 * 连 `autofillId` 都没有，系统根本不会发起填充请求。
 *
 * 所以这里按可靠度从高到低排队：
 *
 * 1. **`autofillHints` 含 `password`** —— 最可信，网站自己说的
 * 2. **`htmlAttributes["type"] == "password"`** —— WebView 的原始 DOM 属性
 * 3. **`inputType` 的密码变体** —— 密码框会带掩码，这个几乎不会错
 * 4. **关键词匹配**（`id`/`name`/`placeholder`/`hint`）—— 兜底，也是唯一能覆盖中文站点的手段
 *
 * ── 两个必须小心的坑 ───────────────────────────────────────
 * - `password` 里含 `word`，`username` 里含 `name`，所以**关键词表不能有 `word`/`name` 这类泛词**，
 *   否则会把用户名框判成密码框，填充时把密码写进明文字段。
 * - 搜索框常写成 `id="q"` / `name="keyword"` / `placeholder="搜索"`，
 *   必须显式排除，否则用户一点搜索框就弹出"填充账号密码"。
 */
object AutofillHints {

    // ── 从可靠到不可靠 ──────────────────────────────────────

    private val HINT_PASSWORD = setOf("password", "newPassword", "currentPassword")
    private val HINT_USERNAME = setOf(
        "username", "emailAddress", "email", "phone", "phoneNumber"
    )
    // 注意：不要把 AUTOFILL_HINT_NAME（值就是 "name"）放进来 —— 它指的是"真实姓名"，
    // 常见于收货地址表单。拿它当登录账号，会把姓名填进登录框。

    // InputType 数值（避免依赖 android 类，保证可单测）
    private const val MASK_CLASS = 0x0000000f
    private const val MASK_VARIATION = 0x00000ff0
    private const val CLASS_TEXT = 0x00000001
    private const val CLASS_NUMBER = 0x00000002
    private const val VARIATION_TEXT_PASSWORD = 0x00000080
    private const val VARIATION_TEXT_VISIBLE_PASSWORD = 0x00000090
    private const val VARIATION_TEXT_WEB_PASSWORD = 0x000000e0
    private const val VARIATION_NUMBER_PASSWORD = 0x00000010

    /** 明确**不是**登录字段的关键词，优先级高于一切正面信号。 */
    private val IGNORE_KEYWORDS = listOf(
        "search", "query", "keyword", "filter", "captcha", "verifycode", "verify_code",
        "smscode", "sms_code", "otp", "totp", "onetime", "code",
        "搜索", "查询", "筛选", "验证码", "短信验证码", "动态码", "图形码"
    )

    private val PASSWORD_KEYWORDS = listOf(
        "password", "passwd", "pwd", "passcode", "pass_word",
        "密码", "密碼", "登录密码", "登陆密码", "口令"
    )

    private val USERNAME_KEYWORDS = listOf(
        "username", "user_name", "userid", "user_id", "account", "acct",
        "email", "mail", "login", "loginname", "phone", "mobile", "tel", "msisdn",
        "账号", "帐号", "账户", "用户名", "用户", "手机号", "手机", "邮箱", "邮箱地址", "号码"
    )

    /**
     * 判定单个字段。
     *
     * 顺序即优先级：先排除、再按证据强度判定，最后才用关键词兜底。
     */
    fun classify(field: FieldSignals): FieldKind {
        if (!field.isEditable) return FieldKind.IGNORE

        val hints = field.autofillHints.map { it.trim() }.filter { it.isNotEmpty() }
        val htmlType = field.htmlAttributes["type"]?.trim()?.lowercase()
        val haystack = buildHaystack(field)

        // 0. 排除项优先于一切：网站把搜索框的 autofillHints 写成 "name" 的情况真实存在
        if (IGNORE_KEYWORDS.any { haystack.contains(it) }) {
            // 但如果 HTML 明确写了 type=password，那它就是密码框（有些站点的验证码框 id 叫 passcode 之类，
            // 冲突时以更硬的证据为准）
            if (htmlType != "password" && !isPasswordInputType(field.inputType)) {
                return FieldKind.IGNORE
            }
        }

        // 1. 网站声明的语义
        if (hints.any { it in HINT_PASSWORD }) return FieldKind.PASSWORD
        if (hints.any { it in HINT_USERNAME }) return FieldKind.USERNAME

        // 2. WebView 的原始 DOM 属性
        if (htmlType == "password") return FieldKind.PASSWORD
        if (htmlType == "email" || htmlType == "tel") return FieldKind.USERNAME

        // 3. 输入类型（密码框必然带掩码变体）
        if (isPasswordInputType(field.inputType)) return FieldKind.PASSWORD

        // 4. 关键词兜底。密码优先于用户名：`userpassword` 这种 id 两者都命中，
        //    但它是密码框 —— 判错的代价是把密码明文填进可见输入框。
        if (PASSWORD_KEYWORDS.any { haystack.contains(it) }) return FieldKind.PASSWORD
        if (USERNAME_KEYWORDS.any { haystack.contains(it) }) return FieldKind.USERNAME

        return FieldKind.UNKNOWN
    }

    /** 是否密码型 inputType（`TEXT` 的密码变体，或 `NUMBER` 的数字密码）。 */
    fun isPasswordInputType(inputType: Int): Boolean {
        val cls = inputType and MASK_CLASS
        val variation = inputType and MASK_VARIATION
        return when (cls) {
            CLASS_TEXT -> variation == VARIATION_TEXT_PASSWORD ||
                variation == VARIATION_TEXT_VISIBLE_PASSWORD ||
                variation == VARIATION_TEXT_WEB_PASSWORD
            CLASS_NUMBER -> variation == VARIATION_NUMBER_PASSWORD
            else -> false
        }
    }

    /**
     * 把字段上所有文本线索拼成一个小写串用于关键词匹配。
     *
     * 只取 `id`/`name`/`placeholder`/`aria-label`/`title` 等**结构性属性**，
     * 不取 `value` —— 用户已经输入的内容不该影响"这是什么字段"的判断。
     */
    private fun buildHaystack(field: FieldSignals): String = buildString {
        field.idEntry?.let { append(it.lowercase()).append(' ') }
        field.hint?.let { append(it.lowercase()).append(' ') }
        val attrs = field.htmlAttributes
        for (key in listOf("name", "id", "placeholder", "aria-label", "title", "autocomplete")) {
            attrs[key]?.let { append(it.lowercase()).append(' ') }
        }
    }
}
