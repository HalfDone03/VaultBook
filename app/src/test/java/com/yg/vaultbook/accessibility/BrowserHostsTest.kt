package com.yg.vaultbook.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 地址栏文本 → 站点标识。
 *
 * 这里每一条 `assertNull` 都比成功的用例更重要：**判断不出来的时候必须返回 null**。
 * 猜错一个，就会把 A 站的账号密码挂到 B 站名下，下次自动填充把凭据填进别人的表单。
 */
class BrowserHostsTest {

    // ── 能认出来的 ───────────────────────────────────────────────

    @Test
    fun `裸域名原样返回`() {
        assertEquals("taobao.com", BrowserHosts.hostFromText("taobao.com"))
    }

    @Test
    fun `完整 URL 剥掉协议路径查询片段`() {
        assertEquals(
            "taobao.com",
            BrowserHosts.hostFromText("https://www.Taobao.com/login?redirect=%2Fhome#top")
        )
    }

    @Test
    fun `剥掉端口`() {
        assertEquals("taobao.com", BrowserHosts.hostFromText("taobao.com:8080"))
    }

    @Test
    fun `子域归一到主域`() {
        assertEquals("taobao.com", BrowserHosts.hostFromText("new.taobao.com"))
    }

    @Test
    fun `二段后缀取三段`() {
        assertEquals("example.co.uk", BrowserHosts.hostFromText("a.example.co.uk"))
    }

    @Test
    fun `非 http 协议也能处理`() {
        assertEquals("example.com", BrowserHosts.hostFromText("ftp://files.example.com/a/b"))
    }

    @Test
    fun `IPv4 保留端口之外的完整地址`() {
        assertEquals("192.168.1.1", BrowserHosts.hostFromText("192.168.1.1:8080"))
    }

    @Test
    fun `IPv6 字面量保留方括号`() {
        assertEquals("[::1]", BrowserHosts.hostFromText("[::1]:8080"))
    }

    @Test
    fun `结尾的点被去掉`() {
        assertEquals("taobao.com", BrowserHosts.hostFromText("taobao.com."))
    }

    // ── 认不出来就必须返回 null ───────────────────────────────────

    @Test
    fun `空与空白返回 null`() {
        assertNull(BrowserHosts.hostFromText(null))
        assertNull(BrowserHosts.hostFromText(""))
        assertNull(BrowserHosts.hostFromText("   "))
    }

    @Test
    fun `中文提示文案不是域名`() {
        assertNull(BrowserHosts.hostFromText("搜索或输入网址"))
        assertNull(BrowserHosts.hostFromText("淘宝网 - 淘！我喜欢"))
    }

    @Test
    fun `纯词不带点不是域名`() {
        // localhost 能匹配本机服务，但地址栏里出现它时通常跟端口一起，
        // 而归一化后的 host 没有点号会让所有"无点文本"都变成合法值，所以一律拒绝。
        assertNull(BrowserHosts.hostFromText("localhost"))
        assertNull(BrowserHosts.hostFromText("淘宝"))
    }

    @Test
    fun `邮箱绝不能被当成域名`() {
        // 账号框里填 alice@example.com 极常见。不拦掉的话，整站会被认成 example.com。
        assertNull(BrowserHosts.hostFromText("alice@example.com"))
        assertNull(BrowserHosts.hostFromText("https://bob:pw@a.com/"))
    }

    @Test
    fun `版本号不是域名`() {
        assertNull(BrowserHosts.hostFromText("1.5"))
        assertNull(BrowserHosts.hostFromText("v1.2.3"))
    }

    @Test
    fun `中文域名暂不支持`() {
        assertNull(BrowserHosts.hostFromText("例子.中国"))
    }

    @Test
    fun `顶级域必须是纯字母且不短于两位`() {
        assertTrue(BrowserHosts.looksLikeHost("a.co"))
        assertFalse(BrowserHosts.looksLikeHost("a.c"))
        assertFalse(BrowserHosts.looksLikeHost("a.c0m"))
    }

    // ── 候选列表 ─────────────────────────────────────────────────

    @Test
    fun `候选列表取第一个能确认的`() {
        val host = BrowserHosts.hostFromCandidates(
            listOf("搜索或输入网址", "淘宝网", "taobao.com")
        )
        assertEquals("taobao.com", host)
    }

    @Test
    fun `候选全都不像域名时返回 null`() {
        assertNull(BrowserHosts.hostFromCandidates(listOf("搜索或输入网址", "淘宝网")))
        assertNull(BrowserHosts.hostFromCandidates(emptyList()))
    }

    // ── 包名白名单 ───────────────────────────────────────────────

    @Test
    fun `白名单判断`() {
        assertTrue(BrowserHosts.isBrowser("com.android.chrome"))
        assertTrue(BrowserHosts.isBrowser("com.tencent.mtt"))
        assertFalse(BrowserHosts.isBrowser("com.example.app"))
        assertFalse(BrowserHosts.isBrowser(null))
    }

    @Test
    fun `地址栏 id 提示`() {
        assertTrue(BrowserHosts.looksLikeAddressBar("url_bar", null))
        assertTrue(BrowserHosts.looksLikeAddressBar(null, "omnibox"))
        assertFalse(BrowserHosts.looksLikeAddressBar("et_search_input", null))
        assertFalse(BrowserHosts.looksLikeAddressBar(null, null))
    }
}
