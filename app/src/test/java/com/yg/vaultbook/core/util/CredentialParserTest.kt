package com.yg.vaultbook.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 粘贴识别。
 *
 * 这里全部用"用户真的会粘进来的形状"来测，而不是构造理想化输入 ——
 * 这个功能的价值就在脏数据上，只在干净数据上通过等于没测。
 */
class CredentialParserTest {

    @Test
    fun `空输入返回 null`() {
        assertNull(CredentialParser.parse(null))
        assertNull(CredentialParser.parse(""))
        assertNull(CredentialParser.parse("   \n\t  "))
    }

    /**
     * 最常见的一种：一行里两个 key。
     *
     * 早期实现会把它读成 username="admin 密码：abc123" —— 值一直取到行末。
     * 这条用例就是钉住那个修复。
     */
    @Test
    fun `一行里两个 key`() {
        val r = CredentialParser.parse("账号：admin 密码：abc123")
        assertNotNull(r)
        assertEquals("admin", r!!.username)
        assertEquals("abc123", r.password)
    }

    @Test
    fun `多行 key value`() {
        val raw = """
            账号: alice
            密码: p@ss w0rd
            网址: https://www.taobao.com/login?redirect=1
        """.trimIndent()
        val r = CredentialParser.parse(raw)!!
        assertEquals("alice", r.username)
        assertEquals("p@ss w0rd", r.password)
        assertEquals("www.taobao.com", r.url)
    }

    @Test
    fun `全角冒号与等号都能识别`() {
        assertEquals("alice", CredentialParser.parse("账号：alice")!!.username)
        assertEquals("alice", CredentialParser.parse("账号＝alice")!!.username)
        assertEquals("alice", CredentialParser.parse("username = alice")!!.username)
    }

    /** `user` 若排在 `username` 前面，`username: alice` 会被切成 `user` + `name: alice`。 */
    @Test
    fun `长 key 优先于短 key`() {
        val r = CredentialParser.parse("username: alice\npassword: pw")!!
        assertEquals("alice", r.username)
        assertEquals("pw", r.password)
    }

    /**
     * 密码里的全角字符、空格、中文标点一律原样保留。
     *
     * 归一化只用于比较 key 名；一旦把取值也归一化，写进库的密码
     * 就和真实密码不一样了，而用户当场看不出来。
     */
    @Test
    fun `密码原样保留不做归一化`() {
        assertEquals("ＡＢＣ１２３", CredentialParser.parse("密码：ＡＢＣ１２３")!!.password)
        assertEquals("我 的 密 码", CredentialParser.parse("密码：我 的 密 码")!!.password)
        assertEquals("abc----123", CredentialParser.parse("密码：abc----123")!!.password)
    }

    @Test
    fun `两行分别是账号和密码`() {
        val r = CredentialParser.parse("admin\nabc123")!!
        assertEquals("admin", r.username)
        assertEquals("abc123", r.password)
    }

    /** 三行且首行是中文短词 → 首行当站点名，不当账号。 */
    @Test
    fun `三行首行是站点名`() {
        val r = CredentialParser.parse("淘宝\n13800138000\np@ssw0rd")!!
        assertEquals("淘宝", r.title)
        assertEquals("13800138000", r.username)
        assertEquals("p@ssw0rd", r.password)
    }

    /** 首行没有中文（像账号），就不能当站点名。 */
    @Test
    fun `首行像账号时不当站点名`() {
        val r = CredentialParser.parse("admin\n13700001111")!!
        assertNull(r.title)
        assertEquals("admin", r.username)
        assertEquals("13700001111", r.password)
    }

    @Test
    fun `单行三个分量`() {
        val r = CredentialParser.parse("淘宝  13800138000  p@ssw0rd")!!
        assertEquals("淘宝", r.title)
        assertEquals("13800138000", r.username)
        assertEquals("p@ssw0rd", r.password)
    }

    @Test
    fun `单行两个分量`() {
        val r = CredentialParser.parse("admin 123456")!!
        assertEquals("admin", r.username)
        assertEquals("123456", r.password)
    }

    @Test
    fun `单行单值只当账号`() {
        val r = CredentialParser.parse("admin")!!
        assertEquals("admin", r.username)
        assertNull(r.password)
    }

    /**
     * 只粘了一个网址时，不能再把它当账号。
     *
     * `www.taobao.com` 会被 cleanUrl 归一成 `taobao.com`，如果残行过滤
     * 不归一化比较，这一行就会漏进"按位置猜"，把网址填进用户名框。
     */
    @Test
    fun `只粘网址时不填账号`() {
        val r = CredentialParser.parse("www.taobao.com")!!
        assertEquals("www.taobao.com", r.url)
        assertNull(r.username)
        assertNull(r.password)
        assertFalse(r.hasCredential)
    }

    @Test
    fun `完整链接只保留主机名`() {
        assertEquals(
            "www.taobao.com",
            CredentialParser.parse("https://www.taobao.com/item/1?x=2#top")!!.url
        )
    }

    @Test
    fun `网址 key 的值会被清洗`() {
        assertEquals("a.com", CredentialParser.parse("网址：https://a.com/b/c")!!.url)
    }

    @Test
    fun `账号加网址混合`() {
        val r = CredentialParser.parse("账号：alice\nhttps://b.com/x")!!
        assertEquals("alice", r.username)
        assertEquals("b.com", r.url)
    }

    @Test
    fun `手机号 key 当账号`() {
        val r = CredentialParser.parse("手机号：13800138000\n登录密码：pw")!!
        assertEquals("13800138000", r.username)
        assertEquals("pw", r.password)
    }

    @Test
    fun `邮箱 key 当账号并保留 at 号`() {
        val r = CredentialParser.parse("邮箱：a@b.com")!!
        assertEquals("a@b.com", r.username)
    }

    @Test
    fun `用户 key 当账号`() {
        assertEquals("x", CredentialParser.parse("用户：x")!!.username)
    }

    @Test
    fun `站点 key 当标题`() {
        val r = CredentialParser.parse("站点：淘宝\n账号：x")!!
        assertEquals("淘宝", r.title)
        assertEquals("x", r.username)
    }

    @Test
    fun `英文 key 也能认`() {
        val r = CredentialParser.parse("title: 京东\npassword: a1b2")!!
        assertEquals("京东", r.title)
        assertEquals("a1b2", r.password)
    }

    @Test
    fun `只有密码也算认出凭据`() {
        val r = CredentialParser.parse("密码：only-pass")!!
        assertNull(r.username)
        assertEquals("only-pass", r.password)
        assertTrue(r.hasCredential)
    }

    @Test
    fun `CRLF 换行等价于 LF`() {
        val r = CredentialParser.parse("admin\r\nabc123")!!
        assertEquals("admin", r.username)
        assertEquals("abc123", r.password)
    }

    @Test
    fun `前后空白与空行被忽略`() {
        val r = CredentialParser.parse("\n\n  账号: alice  \n\n  密码: pw  \n\n")!!
        assertEquals("alice", r.username)
        assertEquals("pw", r.password)
    }

    @Test
    fun `值末尾的逗号分号被去掉`() {
        assertEquals("abc", CredentialParser.parse("密码：abc，;")!!.password)
    }

    @Test
    fun `认不出任何东西时返回 null`() {
        // 一串没有 key、没有网址、没有分隔符的文字，只会被当成一个账号，
        // 所以这里用纯符号来验证"真的什么都认不出"
        assertNull(CredentialParser.parse("。、；：！！！"))
    }

    // ── 多条识别（parseMany）────────────────────────────────────

    @Test
    fun `一行一条的表格形状会拆成多条`() {
        val text = """
            淘宝 13800138000 p@ssw0rd
            京东 myjd 12345678
            知乎 zhihu zhihu2024
        """.trimIndent()

        val rows = CredentialParser.parseMany(text)
        assertEquals(3, rows.size)
        assertEquals("淘宝", rows[0].title)
        assertEquals("13800138000", rows[0].username)
        assertEquals("p@ssw0rd", rows[0].password)
        assertEquals("京东", rows[1].title)
        assertEquals("zhihu2024", rows[2].password)
    }

    @Test
    fun `空行分段的两条会被分开`() {
        val text = """
            账号：admin 密码：abc123
            网址：taobao.com

            账号：root 密码：root888
            网址：jd.com
        """.trimIndent()

        val rows = CredentialParser.parseMany(text)
        assertEquals(2, rows.size)
        assertEquals("admin", rows[0].username)
        assertEquals("root", rows[1].username)
        assertEquals("jd.com", rows[1].url)
    }

    @Test
    fun `同一行里重复出现的密码键会切成两条`() {
        val text = "平台：淘宝 账号：a1 密码：p1 平台：京东 账号：a2 密码：p2"

        val rows = CredentialParser.parseMany(text)
        assertEquals(2, rows.size)
        assertEquals("淘宝", rows[0].title)
        assertEquals("p1", rows[0].password)
        assertEquals("京东", rows[1].title)
        assertEquals("p2", rows[1].password)
    }

    /**
     * 一条记录自己就分成「标题 / 账号 / 密码」三行时，
     * 第 2、3 行不能各自被当成新记录 —— 这是重复 key 切分最容易误伤的形状。
     */
    @Test
    fun `单条记录跨三行不会被拆开`() {
        val text = """
            标题：淘宝
            账号：admin
            密码：abc123
        """.trimIndent()

        val rows = CredentialParser.parseMany(text)
        assertEquals(1, rows.size)
        assertEquals("admin", rows[0].username)
        assertEquals("abc123", rows[0].password)
    }

    @Test
    fun `只有一行时退回单条`() {
        val rows = CredentialParser.parseMany("账号：admin 密码：abc123")
        assertEquals(1, rows.size)
        assertEquals("admin", rows[0].username)
    }

    @Test
    fun `没有凭据的文本返回空列表`() {
        assertTrue(CredentialParser.parseMany("").isEmpty())
        assertTrue(CredentialParser.parseMany(null).isEmpty())
        // 单行文本仍走单条识别（与 parse 一致），只有多组才进批量框
        assertEquals(1, CredentialParser.parseMany("账号：admin 密码：abc123").size)
    }

    @Test
    fun `多条结果里每条都带密码`() {
        val text = """
            网易 13800138000 woaini1314
            微博 weibo_me qwer1234!
        """.trimIndent()

        val rows = CredentialParser.parseMany(text)
        assertEquals(2, rows.size)
        assertTrue(rows.all { !it.password.isNullOrEmpty() })
    }
    @Test
    fun `isEmpty 与 hasCredential 的语义`() {
        val empty = CredentialParser.Result()
        assertTrue(empty.isEmpty)
        assertFalse(empty.hasCredential)

        val urlOnly = CredentialParser.Result(url = "a.com")
        assertFalse(urlOnly.isEmpty)
        assertFalse(urlOnly.hasCredential)
    }
}
