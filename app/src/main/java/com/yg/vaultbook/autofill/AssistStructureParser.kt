package com.yg.vaultbook.autofill

import android.app.assist.AssistStructure
import android.view.View
import android.view.autofill.AutofillId

/** 一个被识别出来的可填字段。 */
data class ParsedField(
    val autofillId: AutofillId,
    val kind: FieldKind,
    /**
     * 字段当前的文本。
     *
     * 注意：`AssistStructure` 只给 `CharSequence`，拿到的就是 Java `String`。
     * 这是系统接口的限制，本项目"密码不进 String"的约定在这里无法完全遵守 ——
     * 因此这个值只做**中转**：立刻加密落库，不留在任何字段、不打印、不缓存。
     */
    val text: String?
)

/**
 * 从 [AssistStructure] 里解析出的登录表单结构。
 *
 * 注意这是**纯结构**，不含任何凭据内容（除了 [ParsedField.text] 的中转值）。
 */
data class ParsedLoginForm(
    /** 浏览器上报的真实域名，如 `taobao.com`。非浏览器场景为 null。 */
    val webDomain: String?,
    /** 前台应用包名，如 `com.taobao.taobao` */
    val packageName: String?,
    val activityTitle: String?,
    val usernameField: ParsedField?,
    val passwordField: ParsedField?,
    /** 页面里密码字段的总数。≥2 通常是注册/改密页，不是登录页。 */
    val passwordFieldCount: Int,
    /** 页面里账号字段的总数 */
    val usernameFieldCount: Int
) {
    val hasPassword: Boolean get() = passwordField != null
    val hasUsername: Boolean get() = usernameField != null

    /** 能确定站点身份 —— 没有它，存下来也无从匹配。 */
    val hasSiteIdentity: Boolean get() = !webDomain.isNullOrBlank() || !packageName.isNullOrBlank()

    /**
     * 是否是"看起来像登录"的表单。
     *
     * 只要求有密码框。**不要求**有账号框：很多银行的密码框是分步输入的
     * （先输卡号，下一页才输密码），那些页面上只有密码框。
     */
    val looksLikeLogin: Boolean get() = hasPassword
}

/**
 * `AssistStructure` → [ParsedLoginForm]。
 *
 * ── 这个类存在的理由 ────────────────────────────────────────
 * `AssistStructure` 是一棵"把 View 层级序列化过来"的树，API 又长又难用，
 * 直接写在 Service 里会让 `onFillRequest` 变成一坨无法测试的嵌套循环。
 * 拆出来后：
 * - Service 只做决策（填不填、存不存）
 * - 树的遍历与启发式判定在这里，逻辑可以独立推敲
 *
 * ── 硬性防御 ────────────────────────────────────────────────
 * 遍历有**深度与节点数上限**。这棵树的形状由被填充的应用决定，
 * 也就是由**第三方**决定；一个异常深或异常宽的树不应该让本服务 OOM 或栈溢出 ——
 * 那会让系统把本服务判为异常并降权，直接影响整个自动填充功能。
 */
object AssistStructureParser {

    private const val MAX_DEPTH = 64
    private const val MAX_NODES = 2_000

    fun parse(structure: AssistStructure?): ParsedLoginForm {
        if (structure == null) {
            return ParsedLoginForm(null, null, null, null, null, 0, 0)
        }

        var webDomain: String? = null
        var packageName: String? = null
        // 页面标题：`ViewNode` 没有 idTitle（那是 AccessibilityNodeInfo 的），
        // 但 `WindowNode.getTitle()` 有 —— 它就是要的东西。等不到时退一步用
        // 当前 Activity 的类名短名（"LoginActivity" 而不是"登录 - 淘宝网"）。
        // 置信度里这一项权重只有 0.1，正是因为它常常拿不准。
        var title: String? = structure.activityComponent?.className?.substringAfterLast('.')

        val usernameCandidates = mutableListOf<ParsedField>()
        val passwordCandidates = mutableListOf<ParsedField>()
        var visited = 0

        for (windowIndex in 0 until structure.windowNodeCount) {
            val window: AssistStructure.WindowNode = structure.getWindowNodeAt(windowIndex) ?: continue

            // getTitle() 拿到的才是真正的窗口标题，能拿到就用它替掉 Activity 类名
            window.title?.toString()?.takeIf { it.isNotBlank() }?.let { title = it }

            // 注意是 rootViewNode（AssistStructure 的命名），不是 AccessibilityNodeInfo 的 rootView
            val root: AssistStructure.ViewNode = window.rootViewNode ?: continue

            // 迭代式遍历：递归在深树上会 StackOverflow，而这个树由第三方构造
            val stack = ArrayDeque<Pair<AssistStructure.ViewNode, Int>>()
            stack.addLast(root to 0)

            while (stack.isNotEmpty()) {
                if (++visited > MAX_NODES) break

                val (node, depth) = stack.removeLast()
                if (depth > MAX_DEPTH) continue

                // 域名/包名在任意节点上都可能带，取第一个非空的
                if (webDomain == null) webDomain = node.webDomain?.takeIf { it.isNotBlank() }
                if (packageName == null) packageName = node.idPackage?.takeIf { it.isNotBlank() }

                val autofillId = node.autofillId
                // ViewNode 没有 isEditable()：能填的判据就是 AUTOFILL_TYPE_TEXT，
                // 非文本控件（开关、列表）在这一步就被排除了。
                if (autofillId != null && node.autofillType == View.AUTOFILL_TYPE_TEXT) {
                    val kind = AutofillHints.classify(node.toSignals())
                    when (kind) {
                        FieldKind.PASSWORD -> passwordCandidates += ParsedField(
                            autofillId = autofillId,
                            kind = kind,
                            text = node.currentText()
                        )
                        FieldKind.USERNAME -> usernameCandidates += ParsedField(
                            autofillId = autofillId,
                            kind = kind,
                            text = node.currentText()
                        )
                        FieldKind.IGNORE, FieldKind.UNKNOWN -> Unit
                    }
                }

                for (childIndex in 0 until node.childCount) {
                    node.getChildAt(childIndex)?.let { stack.addLast(it to depth + 1) }
                }
            }
        }

        return ParsedLoginForm(
            webDomain = webDomain,
            packageName = packageName,
            activityTitle = title,
            usernameField = pickUsername(usernameCandidates),
            passwordField = passwordCandidates.firstOrNull(),
            passwordFieldCount = passwordCandidates.size,
            usernameFieldCount = usernameCandidates.size
        )
    }

    /**
     * 选哪个账号框。
     *
     * 一页里可能有多个被判定为"账号"的输入框（比如"账号"+"手机号"二选一）。
     * 规则：
     * 1. 已经填了内容且不为空的，优先 —— 用户已经动了它，说明它就是当前用来登录的那个
     * 2. 否则取**第一个**：DOM 顺序上账号框几乎总在密码框之前，而
     *    `AssistStructure` 的遍历顺序与视图顺序一致
     *
     * 注意这里**不**按"离密码框最近的"来选 —— 我们没有可靠的位置信息
     * （`AssistStructure` 给的是树序，不是坐标），硬算距离只会增加猜测。
     */
    private fun pickUsername(usernames: List<ParsedField>): ParsedField? {
        if (usernames.isEmpty()) return null

        usernames.firstOrNull { !it.text.isNullOrBlank() }?.let { return it }

        // 都没填过内容时取第一个。注册页常有三个账号框（手机号/邮箱/用户名）+
        // 一个密码框，取第一个依然是最不坏的选择 —— 至少不会把密码填进账号框。
        return usernames.first()
    }

    private fun AssistStructure.ViewNode.toSignals(): FieldSignals {
        val attributes = htmlInfo?.attributes?.associate { it.first to it.second }.orEmpty()
        return FieldSignals(
            idEntry = idEntry,
            hint = hint,
            autofillHints = autofillHints?.toList().orEmpty(),
            htmlAttributes = attributes,
            inputType = inputType,
            // 能走到这里的节点都已经过 AUTOFILL_TYPE_TEXT 过滤，一律视为可编辑
            isEditable = true
        )
    }

    /**
     * 取当前文本。
     *
     * 优先 `autofillValue`：保存请求里系统把用户输入放进这里；
     * 退回 `text`：某些实现只填了 `text`。
     */
    private fun AssistStructure.ViewNode.currentText(): String? =
        autofillValue?.textValue?.toString()
            ?: text?.toString()?.takeIf { it.isNotEmpty() }
}
