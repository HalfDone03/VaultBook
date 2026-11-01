package com.yg.vaultbook.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Pinyin] 的行为测试。
 *
 * 这个类的正确性完全取决于 `BLOCK_STARTS` 那 24 个数字对不对 —— 错一位，
 * 整段汉字的首字母就全歪。所以测试的重点不是算法（二分查找没什么好测的），
 * 而是**用一批真实字词去钉住那张表**：
 *
 *   - 每个声母挑 2–3 个代表字，逐个断言
 *   - 用真实应用名去断言（「淘宝」→`tb`、「支付宝」→`zfb`）
 *   - 边界字：a 区第一个（啊）、z 区最后一个（座）
 *
 * 如果哪天有人手贱改了表，这些断言会立刻炸掉，而不是悄悄让搜索错位。
 */
class PinyinTest {

    // ── 单字 ────────────────────────────────────────────────

    @Test
    fun `23 个声母各有代表字`() {
        // 按 ALPHABET 顺序挑的常用字，一个声母一个
        val samples = listOf(
            'a' to '阿', 'b' to '八', 'c' to '擦', 'd' to '搭', 'e' to '蛾',
            'f' to '发', 'g' to '噶', 'h' to '哈', 'j' to '击', 'k' to '喀',
            'l' to '垃', 'm' to '妈', 'n' to '拿', 'o' to '哦', 'p' to '啪',
            'q' to '期', 'r' to '然', 's' to '撒', 't' to '塌', 'w' to '挖',
            'x' to '昔', 'y' to '压', 'z' to '匝'
        )
        samples.forEach { (expect, ch) ->
            assertEquals("「$ch」的首字母", expect, Pinyin.initialOf(ch))
        }
    }

    @Test
    fun `一级汉字区首位两端都正确`() {
        // a 区第一个是「啊」，z 区最后一个是「座」——
        // 这两个字正好卡在 3755 长度的两端，最能暴露越界问题
        assertEquals('a', Pinyin.initialOf('啊'))
        assertEquals('z', Pinyin.initialOf('座'))
    }

    @Test
    fun `各区尾部代表字`() {
        // 取每个声母区块接近末尾的字，验证上边界没串到下一个声母
        assertEquals('a', Pinyin.initialOf('吧'))
        assertEquals('b', Pinyin.initialOf('部'))
        assertEquals('c', Pinyin.initialOf('错'))
        assertEquals('d', Pinyin.initialOf('堕'))
        assertEquals('e', Pinyin.initialOf('贰'))
        assertEquals('f', Pinyin.initialOf('咐'))
        assertEquals('g', Pinyin.initialOf('过'))
        assertEquals('h', Pinyin.initialOf('祸'))
        assertEquals('j', Pinyin.initialOf('骏'))
        assertEquals('k', Pinyin.initialOf('阔'))
        assertEquals('l', Pinyin.initialOf('络'))
        assertEquals('m', Pinyin.initialOf('穆'))
        assertEquals('n', Pinyin.initialOf('诺'))
        assertEquals('o', Pinyin.initialOf('沤'))
        assertEquals('p', Pinyin.initialOf('瀑'))
        assertEquals('q', Pinyin.initialOf('群'))
        assertEquals('r', Pinyin.initialOf('弱'))
        assertEquals('s', Pinyin.initialOf('所'))
        assertEquals('t', Pinyin.initialOf('唾'))
        assertEquals('w', Pinyin.initialOf('误'))
        assertEquals('x', Pinyin.initialOf('迅'))
        assertEquals('y', Pinyin.initialOf('孕'))
        assertEquals('z', Pinyin.initialOf('座'))
    }

    @Test
    fun `常见应用名的首字母逐个正确`() {
        assertEquals('t', Pinyin.initialOf('淘'))
        assertEquals('b', Pinyin.initialOf('宝'))
        assertEquals('z', Pinyin.initialOf('支'))
        assertEquals('f', Pinyin.initialOf('付'))
        assertEquals('j', Pinyin.initialOf('京'))
        assertEquals('d', Pinyin.initialOf('东'))
        assertEquals('w', Pinyin.initialOf('微'))
        assertEquals('x', Pinyin.initialOf('信'))
        assertEquals('y', Pinyin.initialOf('银'))
        assertEquals('q', Pinyin.initialOf('器'))
        assertEquals('b', Pinyin.initialOf('笔'))
    }

    @Test
    fun `ASCII 字母数字原样返回且转小写`() {
        assertEquals('a', Pinyin.initialOf('a'))
        assertEquals('j', Pinyin.initialOf('J'))
        assertEquals('d', Pinyin.initialOf('D'))
        assertEquals('5', Pinyin.initialOf('5'))
    }

    @Test
    fun `ASCII 符号与空格不算首字母`() {
        assertEquals(Pinyin.NONE, Pinyin.initialOf('-'))
        assertEquals(Pinyin.NONE, Pinyin.initialOf(' '))
        assertEquals(Pinyin.NONE, Pinyin.initialOf('.'))
        assertEquals(Pinyin.NONE, Pinyin.initialOf('@'))
    }

    @Test
    fun `生僻字与二级汉字返回 NONE`() {
        // 「燚」是 GBK 扩展区的四叠火，不在 GB2312 一级汉字
        assertEquals(Pinyin.NONE, Pinyin.initialOf('燚'))
        // 「亍」属二级汉字（0xD8 起），按设计不处理
        assertEquals(Pinyin.NONE, Pinyin.initialOf('亍'))
    }

    @Test
    fun `全角字母不算首字母`() {
        // 全角 Ａ 由 Normalize 负责转半角，Pinyin 这一层不管
        assertEquals(Pinyin.NONE, Pinyin.initialOf('Ａ'))
    }

    @Test
    fun `多音字取 GB2312 排的那个音`() {
        // 「行」排在 x 区（xing），不是 h（hang）—— 这是数据固有限制，
        // 断言它只是把这个已知行为固定下来，避免被误当成 bug 去"修"
        assertEquals('x', Pinyin.initialOf('行'))
        // 「重」排在 z 区（zhong），不是 c（chong）
        assertEquals('z', Pinyin.initialOf('重'))
    }

    // ── 整串 ────────────────────────────────────────────────

    @Test
    fun `整串首字母`() {
        assertEquals("zfb", Pinyin.initials("支付宝"))
        assertEquals("tb", Pinyin.initials("淘宝"))
        assertEquals("jd", Pinyin.initials("京东"))
        assertEquals("wx", Pinyin.initials("微信"))
        assertEquals("yysd", Pinyin.initials("应用商店"))
    }

    @Test
    fun `整串里混着数字与英文`() {
        assertEquals("tb5", Pinyin.initials("淘宝5"))
        assertEquals("wifi", Pinyin.initials("Wi-Fi"))
        assertEquals("q3", Pinyin.initials("Q3"))
    }

    @Test
    fun `整串跳过标点与空白`() {
        assertEquals("zfb", Pinyin.initials("支 付 宝"))
        assertEquals("zfb", Pinyin.initials("（支付宝）"))
        assertEquals("tb", Pinyin.initials("淘 / 宝"))
    }

    @Test
    fun `空串与纯符号`() {
        assertEquals("", Pinyin.initials(""))
        assertEquals("", Pinyin.initials("——"))
        assertEquals("", Pinyin.initials("！！！"))
    }

    @Test
    fun `生僻字被跳过但其余字仍在`() {
        // 「燚」没有首字母，不影响前后的字
        assertEquals("ab", Pinyin.initials("阿燚八"))
    }

    // ── 搜索键 ──────────────────────────────────────────────

    @Test
    fun `搜索键同时含原文与首字母`() {
        val key = Pinyin.searchKey("支付宝")
        assertTrue("原文应保留", key.contains("支付宝"))
        assertTrue("首字母应在", key.contains("zfb"))
    }

    @Test
    fun `搜索键里原文与首字母用空格分隔`() {
        assertEquals("支付宝 zfb", Pinyin.searchKey("支付宝"))
    }

    @Test
    fun `搜 zfb 能命中支付宝`() {
        val key = Pinyin.searchKey("支付宝")
        assertTrue(key.contains(Normalize.forSearch("zfb")))
    }

    @Test
    fun `搜 tb 能命中淘宝`() {
        val key = Pinyin.searchKey("淘宝")
        assertTrue(key.contains(Normalize.forSearch("tb")))
    }

    @Test
    fun `搜中文仍按原文命中`() {
        val key = Pinyin.searchKey("支付宝")
        assertTrue(key.contains(Normalize.forSearch("支付")))
    }

    @Test
    fun `纯英文内容不产生多余空格`() {
        // "taobao.com" 没有汉字，首字母串就是它自己，
        // 但为避免"原文 + 空格 + 同样的串"这种浪费，这里只保证不含双空格
        val key = Pinyin.searchKey("taobao.com")
        assertTrue(key.isNotBlank())
        assertTrue(!key.contains("  "))
        assertTrue(key.contains("taobao"))
    }

    @Test
    fun `空输入不抛异常`() {
        assertEquals("", Pinyin.searchKey(""))
        assertEquals("", Pinyin.initials(""))
    }

    @Test
    fun `同一字符多次调用结果一致`() {
        // 覆盖缓存路径：第二次走 HashMap，不能和第一次不一致
        val first = Pinyin.initialOf('淘')
        repeat(5) { assertEquals(first, Pinyin.initialOf('淘')) }
    }

    @Test
    fun `大小写不同的英文查询能互相命中`() {
        // 索引是小写，查询经 Normalize 也转小写
        val key = Pinyin.searchKey("GitHub")
        assertTrue(key.contains(Normalize.forSearch("github")))
        assertTrue(key.contains(Normalize.forSearch("GITHUB")))
    }

    @Test
    fun `不会因为前缀相同而互相误伤`() {
        // 「支付宝」zfb 与「招行」zh 前缀都是 z，但完整串不同 ——
        // 说明匹配用的是 contains 完整子串，不是"首字母相同就算命中"
        assertNotEquals(Pinyin.initials("支付宝"), Pinyin.initials("招行"))

        // 搜 zfbh 不该命中支付宝：多出来的 h 让它不是原串的子串
        val zfb = Pinyin.searchKey("支付宝")
        assertTrue("zfbh 不该命中支付宝", !zfb.contains("zfbh"))
    }
}
