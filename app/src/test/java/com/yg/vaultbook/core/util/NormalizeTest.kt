package com.yg.vaultbook.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 主机名归一化的测试。
 *
 * 这是自动填充匹配的地基：算错一次，用户手填的那条记录就永远匹配不上，
 * 而"匹配不上"在界面上表现为"什么都没发生"，几乎无法排查。所以这里测得比较细。
 */
class NormalizeTest {

    // ── hostFromUrl ────────────────────────────────────────────────

    @Test
    fun `null 与空白返回 null`() {
        assertNull(Normalize.hostFromUrl(null))
        assertNull(Normalize.hostFromUrl(""))
        assertNull(Normalize.hostFromUrl("   "))
    }

    @Test
    fun `裸域名原样保留`() {
        assertEquals("taobao.com", Normalize.hostFromUrl("taobao.com"))
    }

    @Test
    fun `去协议 去路径 去查询 去 www 并转小写`() {
        assertEquals(
            "taobao.com",
            Normalize.hostFromUrl("https://www.Taobao.com/login?redirect=%2Fhome#top")
        )
    }

    @Test
    fun `保留子域但归并到可注册域`() {
        // new.taobao.com 与 taobao.com 语义上是同一个站
        assertEquals("taobao.com", Normalize.hostFromUrl("https://new.taobao.com/"))
    }

    @Test
    fun `二级后缀取三段`() {
        assertEquals("example.co.uk", Normalize.hostFromUrl("https://a.example.co.uk/path"))
        assertEquals("example.com.cn", Normalize.hostFromUrl("http://m.example.com.cn"))
    }

    @Test
    fun `表外多段后缀的已知局限`() {
        // 记录当前行为：github.io 不在 SECOND_LEVEL_SUFFIXES 里，所以只保留两段。
        // 正确结果应是 example.github.io。P2 接入 Public Suffix List 后这个测试要改。
        assertEquals("github.io", Normalize.hostFromUrl("https://me.example.github.io/x"))
    }

    @Test
    fun `去掉 userinfo 与端口`() {
        assertEquals("example.com", Normalize.hostFromUrl("http://user:secret@example.com:8080/p"))
    }

    @Test
    fun `IPv4 原样返回`() {
        assertEquals("192.168.1.1", Normalize.hostFromUrl("http://192.168.1.1:8080/admin"))
    }

    @Test
    fun `IPv6 保留方括号`() {
        assertEquals("[::1]", Normalize.hostFromUrl("http://[::1]:8080/x"))
    }

    @Test
    fun `末尾的点被去掉`() {
        assertEquals("taobao.com", Normalize.hostFromUrl("taobao.com."))
    }

    @Test
    fun `无点的内网主机名原样返回`() {
        assertEquals("localhost", Normalize.hostFromUrl("http://localhost:3000"))
    }

    // ── forSearch ──────────────────────────────────────────────────

    @Test
    fun `搜索归一化 小写并折叠空白`() {
        assertEquals("hello world", Normalize.forSearch("  Hello   World  "))
    }

    @Test
    fun `搜索归一化 全角转半角`() {
        assertEquals("abc123", Normalize.forSearch("ＡＢＣ１２３"))
    }

    @Test
    fun `搜索归一化 空串仍是空串`() {
        assertEquals("", Normalize.forSearch(""))
        assertEquals("", Normalize.forSearch("   "))
    }

    // ── toHalfWidth ────────────────────────────────────────────────

    @Test
    fun `全角空格映射为半角空格`() {
        assertEquals(' ', Normalize.toHalfWidth('\u3000'))
    }

    @Test
    fun `全角 ASCII 映射为半角`() {
        assertEquals('A', Normalize.toHalfWidth('Ａ'))
        assertEquals('!', Normalize.toHalfWidth('！'))
        assertEquals('~', Normalize.toHalfWidth('～'))
    }

    @Test
    fun `非全角字符原样返回`() {
        assertEquals('a', Normalize.toHalfWidth('a'))
        assertEquals('中', Normalize.toHalfWidth('中'))
    }
}
