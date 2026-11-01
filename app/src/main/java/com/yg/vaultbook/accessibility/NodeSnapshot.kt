package com.yg.vaultbook.accessibility

/**
 * 节点树的一次快照 —— 纯数据，不依赖任何 framework 类。
 *
 * ── 为什么要多这一层 ──────────────────────────────────────────
 * `AccessibilityNodeInfo` 是 system_server 那边的代理对象，无法在 JVM 单测里构造。
 * 而识别登录页的逻辑（哪个是账号框、哪个是密码框、哪个是提交按钮）恰恰是
 * 最需要被测试覆盖的部分 —— 判错一次就会把别处的密码存进错误的条目。
 *
 * 所以把「读节点树」和「判断这是什么页面」彻底拆开：
 * - [CaptureAccessibilityService] 负责把 `AccessibilityNodeInfo` 拍平成这个列表；
 * - [A11yFormDetector] 只吃这个列表，纯逻辑，可单测。
 *
 * ── 顺序即语义 ────────────────────────────────────────────────
 * 列表是**深度优先的前序**顺序，也就是用户在页面上从上到下的阅读顺序。
 * [A11yFormDetector] 依赖这个顺序来找「密码框之前的最后一个输入框」——
 * 那是账号框最可靠的定位方式（不依赖各家自定义的 viewId）。
 *
 * @param className 节点类名全称，如 `android.widget.EditText`。自绘页面可能为空。
 * @param viewId 资源 id 名（`viewIdResourceName`），WebView 与 Flutter 里通常为空。
 * @param text 已显示文本。密码框里这个字段是**真实内容**（系统不解密遮蔽），
 *             所以全程不打印、不进日志、用完置 null。
 * @param hint 提示文本（`hintText`），常写着"请输入密码"之类。
 * @param description 无障碍描述（`contentDescription`）。
 * @param editable 是否可编辑（`isEditable`）。
 * @param password 是否密码框（`isPassword`）—— 这是系统给的硬信号，比任何文案都可靠。
 * @param clickable 是否可点击（`isClickable`）。
 * @param visible 是否对用户可见（`isVisibleToUser`）。
 * @param depth 在树中的深度，0 为根。用于给"页面标题"这种取最浅节点的判断提供依据。
 */
data class NodeSnapshot(
    val className: String? = null,
    val viewId: String? = null,
    val text: String? = null,
    val hint: String? = null,
    val description: String? = null,
    val editable: Boolean = false,
    val password: Boolean = false,
    val clickable: Boolean = false,
    val visible: Boolean = true,
    val depth: Int = 0
) {

    /** 有非空文本。空串与只有空白都算没有。 */
    val hasText: Boolean
        get() = !text.isNullOrBlank()

    /**
     * 参与关键词匹配的所有文案，已小写。
     *
     * 注意**不含 [text]**：用户已经输入的内容不该影响"这是什么字段"的判断，
     * 否则填了 `password123` 的账号框会因为文本里带 password 而被当成密码框。
     * 这个坑和自动填充那边 [com.yg.vaultbook.autofill.AutofillHints] 的
     * `buildHaystack` 是同一个道理。
     */
    fun haystack(): String {
        val sb = StringBuilder()
        listOf(viewId, hint, description, className).forEach { raw ->
            if (!raw.isNullOrBlank()) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(raw)
            }
        }
        return sb.toString().lowercase()
    }

    /** 类名是否以某后缀结尾（大小写不敏感）。自绘页面可能给空类名，所以先判空。 */
    fun classEndsWith(suffix: String): Boolean =
        className?.endsWith(suffix, ignoreCase = true) == true
}
