package com.yg.vaultbook.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 从节点快照里认登录表单。
 *
 * 这是 W3 里唯一真正有"判断"的地方 —— 判错一个字段，就会把密码存进错误的位置，
 * 或者把搜索关键词存成账号。所以用例覆盖的是各种真实页面的节点形态，
 * 而不是照着实现写一遍。
 */
class A11yFormDetectorTest {

    private fun pwd(
        text: String? = null,
        hint: String? = null,
        visible: Boolean = true
    ) = NodeSnapshot(
        className = "android.widget.EditText",
        text = text,
        hint = hint,
        editable = true,
        password = true,
        visible = visible,
        depth = 3
    )

    private fun field(
        text: String? = null,
        hint: String? = null,
        visible: Boolean = true
    ) = NodeSnapshot(
        className = "android.widget.EditText",
        text = text,
        hint = hint,
        editable = true,
        visible = visible,
        depth = 3
    )

    private fun button(text: String) =
        NodeSnapshot(className = "android.widget.Button", text = text, clickable = true, depth = 4)

    private fun label(text: String, depth: Int = 1) = NodeSnapshot(text = text, depth = depth)

    // ── 基本形态 ─────────────────────────────────────────────────

    @Test
    fun `空树什么也认不出`() {
        val form = A11yFormDetector.detect(emptyList())
        assertEquals(0, form.passwordFieldCount)
        assertFalse(form.looksLikeLogin)
        assertNull(form.password)
    }

    @Test
    fun `密码框按系统标志位识别并取值`() {
        val form = A11yFormDetector.detect(listOf(label("登录"), field("alice", "账号"), pwd("s3cret")))
        assertTrue(form.hasPassword)
        assertEquals("s3cret", form.password)
    }

    @Test
    fun `账号框按 hint 文案识别`() {
        val form = A11yFormDetector.detect(listOf(field("alice", "请输入用户名"), pwd("pw")))
        assertEquals("alice", form.username)
        assertTrue(form.hasUsername)
    }

    @Test
    fun `没有文案时按树序取密码框之前最后一个可编辑框`() {
        // Flutter / RN 自绘页面的常态：类名可能是一堆 View，hint 为空。
        val form = A11yFormDetector.detect(
            listOf(field("anything"), field("alice"), pwd("pw"))
        )
        assertEquals("alice", form.username)
    }

    @Test
    fun `密码框之后的可编辑框不算账号框`() {
        val form = A11yFormDetector.detect(listOf(pwd("pw"), field("后面的输入框")))
        assertNull(form.username)
        assertFalse(form.hasUsername)
    }

    @Test
    fun `搜索框不算账号框`() {
        val form = A11yFormDetector.detect(
            listOf(field("连衣裙", "搜索商品"), pwd("pw"))
        )
        assertNull(form.username)
    }

    @Test
    fun `两个密码框判为注册页`() {
        val form = A11yFormDetector.detect(listOf(field("alice"), pwd("new1"), pwd("new1")))
        assertTrue(form.looksLikeRegistration)
        assertEquals(2, form.passwordFieldCount)
    }

    @Test
    fun `注册页的密码取第一个密码框`() {
        val form = A11yFormDetector.detect(listOf(pwd("first"), pwd("second")))
        assertEquals("first", form.password)
    }

    // ── 识别信号 ─────────────────────────────────────────────────

    @Test
    fun `密码框可凭 hint 识别`() {
        // 自绘控件不设 isPassword，但 hint 写着「请输入密码」。
        val form = A11yFormDetector.detect(
            listOf(field(null, "请输入密码", visible = true), NodeSnapshot(text = "登录页", depth = 1))
        )
        assertEquals(1, form.passwordFieldCount)
    }

    @Test
    fun `搜索关键词压过密码字样`() {
        // 'search password' 这种 hint 出现在"搜索密码本"之类的页面上，
        // 把它当密码框会污染整个识别结果。搜索类关键词优先级最高。
        val form = A11yFormDetector.detect(listOf(field("x", "search password")))
        assertEquals(0, form.passwordFieldCount)
    }

    @Test
    fun `不可见节点被忽略`() {
        val form = A11yFormDetector.detect(listOf(pwd("pw", visible = false)))
        assertEquals(0, form.passwordFieldCount)
    }

    @Test
    fun `密码框没填值时 password 为 null`() {
        val form = A11yFormDetector.detect(listOf(field("alice", "账号"), pwd(null)))
        assertTrue(form.hasPassword)
        assertNull(form.password)
    }

    // ── 提交按钮 ─────────────────────────────────────────────────

    @Test
    fun `收集可点击的提交按钮文案`() {
        val form = A11yFormDetector.detect(listOf(field("alice", "账号"), pwd("pw"), button("登录")))
        assertTrue(form.submitLabels.contains("登录"))
    }

    @Test
    fun `不可点击的登录字样不算提交按钮`() {
        val form = A11yFormDetector.detect(listOf(pwd("pw"), label("还没有账号？请登录")))
        assertTrue(form.submitLabels.isEmpty())
    }

    @Test
    fun `isSubmitLabel 覆盖常见文案`() {
        assertTrue(A11yFormDetector.isSubmitLabel("登录"))
        assertTrue(A11yFormDetector.isSubmitLabel("立即登录"))
        assertTrue(A11yFormDetector.isSubmitLabel("  Sign In  "))
        assertTrue(A11yFormDetector.isSubmitLabel("下一步"))
    }

    @Test
    fun `isSubmitLabel 拒绝非提交文案`() {
        assertFalse(A11yFormDetector.isSubmitLabel("注册"))
        assertFalse(A11yFormDetector.isSubmitLabel("忘记密码"))
        assertFalse(A11yFormDetector.isSubmitLabel(null))
        assertFalse(A11yFormDetector.isSubmitLabel(""))
        assertFalse(A11yFormDetector.isSubmitLabel("这是一段很长很长的说明文字，不可能是按钮"))
    }

    // ── 站点候选 ─────────────────────────────────────────────────

    @Test
    fun `站点候选不含账号框内容`() {
        val form = A11yFormDetector.detect(
            listOf(field("alice@example.com"), pwd("pw"), label("登录 - 淘宝网"))
        )
        assertFalse(form.siteCandidates.any { it.contains("@") })
    }

    @Test
    fun `站点候选不含提交按钮文案`() {
        val form = A11yFormDetector.detect(listOf(pwd("pw"), button("登录"), label("淘宝网")))
        assertFalse(form.siteCandidates.contains("登录"))
    }

    @Test
    fun `地址栏候选排在普通文本前面`() {
        val form = A11yFormDetector.detect(
            listOf(
                label("淘宝网 - 淘我喜欢", depth = 1),
                NodeSnapshot(text = "taobao.com", viewId = "url_bar", depth = 2),
                field("alice"),
                pwd("pw")
            )
        )
        assertEquals("taobao.com", form.siteCandidates.first())
    }

    @Test
    fun `页面标题取最浅节点且允许含登录二字`() {
        val form = A11yFormDetector.detect(
            listOf(label("登录 - 淘宝网", depth = 1), pwd("pw"), button("登录"))
        )
        // 标题里的"登录"不该让它被当成按钮排除掉
        assertEquals("登录 - 淘宝网", form.pageTitle)
    }

    // ── 浏览器：标题不能是浏览器名 ────────────────────────────────

    @Test
    fun `浏览器里标题取网页正文而不是最浅的浏览器名`() {
        // 真实形态：夸克把工具栏文案摆在 depth 1，网页内容在 WebView 里更深
        val nodes = listOf(
            label("夸克", depth = 1),
            label("QQ邮箱 - 登录", depth = 5),
            field("demo_user@example.com", "邮箱账号").copy(depth = 6),
            pwd("Zx8Kq2Lm7V").copy(depth = 6)
        )
        val form = A11yFormDetector.detect(
            nodes,
            pageChrome = setOf("夸克", "夸克浏览器"),
            preferContentTitle = true
        )
        assertEquals("QQ邮箱", form.pageTitle)
    }

    @Test
    fun `浏览器里只剩宿主文案时标题留空也不拿它顶上`() {
        val nodes = listOf(label("夸克", depth = 1), pwd("pw").copy(depth = 6))
        val form = A11yFormDetector.detect(
            nodes,
            pageChrome = setOf("夸克"),
            preferContentTitle = true
        )
        assertNull(form.pageTitle)
    }

    @Test
    fun `浏览器里工具栏占位字不算标题`() {
        val nodes = listOf(
            label("搜索或输入网址", depth = 1),
            label("新建标签页", depth = 2),
            pwd("pw").copy(depth = 6)
        )
        val form = A11yFormDetector.detect(nodes, preferContentTitle = true)
        assertNull(form.pageTitle)
    }

    @Test
    fun `浏览器里网页标题前后带登录字样时剪成站点名`() {
        val nodes = listOf(
            label("夸克", depth = 1),
            label("登录 | QQ邮箱", depth = 5),
            pwd("pw").copy(depth = 6)
        )
        val form = A11yFormDetector.detect(
            nodes,
            pageChrome = setOf("夸克"),
            preferContentTitle = true
        )
        assertEquals("QQ邮箱", form.pageTitle)
    }

    @Test
    fun `非浏览器保持原样仍取最浅节点`() {
        val form = A11yFormDetector.detect(
            listOf(
                label("淘宝", depth = 1),
                field("tb_1", "账号").copy(depth = 2),
                pwd("pw").copy(depth = 2)
            )
        )
        assertEquals("淘宝", form.pageTitle)
    }
}
