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
    // ── 「名称紧贴密码」的连写（用户 2026-11-02 报的 bug）──────────

    /**
     * 用户报的形状：三行「平台名紧贴密码」，既没空格也没冒号。
     *
     * 早先三条切分规则全都认不出，最后退化成「第一行当账号、第二行当密码、
     * 第三行丢掉」——标题丢失、账号变成第一行整行。
     *
     * 这里用编造的示例数据，形状与用户给的一致，但值不是任何人的真实密码。
     */
    @Test
    fun `三行连写拆成三条`() {
        val text = """
            云盘账户密码Zx8Kq2Lm7V
            云盘Zx8kq2lm7v.
            邮箱平台密码Zx8kq2lm7v.
        """.trimIndent()

        val rows = CredentialParser.parseMany(text)
        assertEquals(3, rows.size)

        assertEquals("云盘账户", rows[0].title)
        assertEquals("Zx8Kq2Lm7V", rows[0].password)
        assertNull(rows[0].username)

        assertEquals("云盘", rows[1].title)
        assertEquals("Zx8kq2lm7v.", rows[1].password)

        assertEquals("邮箱平台", rows[2].title)
        assertEquals("Zx8kq2lm7v.", rows[2].password)
    }

    @Test
    fun `单行连写拆出标题和密码`() {
        val r = CredentialParser.parse("云盘账户密码Zx8Kq2Lm7V")!!
        assertEquals("云盘账户", r.title)
        assertEquals("Zx8Kq2Lm7V", r.password)
        assertNull(r.username)
        assertTrue(r.hasCredential)
    }

    /** 名称里写的是「账号」而不是「密码」，尾巴又是手机号 → 该进账号框。 */
    @Test
    fun `连写里的账号标签按账号处理`() {
        val r = CredentialParser.parse("支付宝账号13800138000")!!
        assertEquals("支付宝", r.title)
        assertEquals("13800138000", r.username)
        assertNull(r.password)
    }

    /** 尾巴是纯数字时不敢当密码：`淘宝13800138000` 多半是账号。 */
    @Test
    fun `连写里纯数字的尾巴当账号`() {
        val r = CredentialParser.parse("淘宝13800138000")!!
        assertEquals("淘宝", r.title)
        assertEquals("13800138000", r.username)
        assertNull(r.password)
    }

    /**
     * 「账户」这两个字本身是平台名的一部分时不能削掉。
     *
     * `云盘账户Zx8kq2lm7v` 的标题必须还是「云盘账户」，而不是「云盘」；
     * 只有明确写了「密码」当字段名时才剥（见上面第一条用例）。
     */
    @Test
    fun `平台名自带的账户二字保留`() {
        val r = CredentialParser.parse("云盘账户Zx8kq2lm7v")!!
        assertEquals("云盘账户", r.title)
        assertEquals("Zx8kq2lm7v", r.password)
    }

    /** 有空格的一行一条是"多列"形状，不能被连写规则抢走。 */
    @Test
    fun `带空格的列仍按列拆`() {
        val text = """
            淘宝 13800138000 p@ssw0rd
            京东 myjd 12345678
        """.trimIndent()

        val rows = CredentialParser.parseMany(text)
        assertEquals(2, rows.size)
        assertEquals("淘宝", rows[0].title)
        assertEquals("13800138000", rows[0].username)
        assertEquals("p@ssw0rd", rows[0].password)
        assertEquals("京东", rows[1].title)
    }

    /** 带冒号的行属于 key: value，连写规则必须让它先走。 */
    @Test
    fun `带冒号的行仍走 key value`() {
        val r = CredentialParser.parse("密码：abc123")!!
        assertEquals("abc123", r.password)
        assertNull(r.title)
        assertNull(r.username)
    }

    // ── 更多抄法（1.6：把常见形状补齐）────────────────────────────

    /** 记事本里手抄时很多人不打冒号，只用一个空格。 */
    @Test
    fun `空白分隔的字段名也能认`() {
        val r = CredentialParser.parse("用户名 admin\n密码 abc123")!!
        assertEquals("admin", r.username)
        assertEquals("abc123", r.password)
    }

    @Test
    fun `一行里空白分隔的两个字段`() {
        val r = CredentialParser.parse("账号 admin 密码 abc123")!!
        assertEquals("admin", r.username)
        assertEquals("abc123", r.password)
    }

    /** 字段名自己占一行、值在下一行。 */
    @Test
    fun `字段名独占一行时值取下一行`() {
        val r = CredentialParser.parse("密码\nabc123")!!
        assertEquals("abc123", r.password)
        assertNull(r.username)
    }

    /** 账号和密码用一个冒号直接连起来，中间没有空白。 */
    @Test
    fun `账号和密码用一个冒号连着`() {
        val r = CredentialParser.parse("zhang@qq.com:abc123")!!
        assertEquals("zhang@qq.com", r.username)
        assertEquals("abc123", r.password)
    }

    /** 从表格里复制时第一行是表头，不能被记成一条"账号叫平台"的记录。 */
    @Test
    fun `表头行不会被当成一条记录`() {
        val text = """
            平台 账号 密码
            淘宝 13800138000 p@ssw0rd
            京东 myjd 12345678
        """.trimIndent()

        val rows = CredentialParser.parseMany(text)
        assertEquals(2, rows.size)
        assertEquals("淘宝", rows[0].title)
        assertEquals("13800138000", rows[0].username)
        assertEquals("京东", rows[1].title)
    }

    /** 记事本里用 `---` 隔开下一条。 */
    @Test
    fun `整行横线当分隔`() {
        val text = """
            淘宝
            13800138000
            p@ssw0rd
            ---
            京东
            myjd
            12345678
        """.trimIndent()

        val rows = CredentialParser.parseMany(text)
        assertEquals(2, rows.size)
        assertEquals("淘宝", rows[0].title)
        assertEquals("13800138000", rows[0].username)
        assertEquals("京东", rows[1].title)
        assertEquals("12345678", rows[1].password)
    }

    /** 抄的时候带序号，序号不能跑进标题。 */
    @Test
    fun `行首序号被剥掉`() {
        val text = """
            1. 淘宝 13800138000 p@ssw0rd
            2. 京东 myjd 12345678
        """.trimIndent()

        val rows = CredentialParser.parseMany(text)
        assertEquals(2, rows.size)
        assertEquals("淘宝", rows[0].title)
        assertEquals("13800138000", rows[0].username)
        assertEquals("京东", rows[1].title)
    }

    /** 标签写在最前面：`账号admin密码abc123`。 */
    @Test
    fun `账号写在连写最前面`() {
        val r = CredentialParser.parse("账号admin密码abc123")!!
        assertEquals("admin", r.username)
        assertEquals("abc123", r.password)
        assertNull(r.title)
    }

    /** 两行、首行是平台名时，它是标题而不是账号。 */
    @Test
    fun `两行首行是站点名时当标题`() {
        val r = CredentialParser.parse("淘宝\nabc123")!!
        assertEquals("淘宝", r.title)
        assertEquals("abc123", r.password)
        assertNull(r.username)
    }

    /** 一条记录被空行掰成「只有账号」「只有密码」两半时，不能算两条。 */
    @Test
    fun `空行把一条记录拆开时不算两条`() {
        val rows = CredentialParser.parseMany("账号：admin\n\n密码：abc123")
        assertEquals(1, rows.size)
        assertEquals("admin", rows[0].username)
        assertEquals("abc123", rows[0].password)
    }

    /** 备注行不是凭据，不能被记成一条记录。 */
    @Test
    fun `备注行不参与识别`() {
        val text = """
            淘宝 13800138000 p@ssw0rd
            备注：备用密码在邮箱里
        """.trimIndent()

        val rows = CredentialParser.parseMany(text)
        assertEquals(1, rows.size)
        assertEquals("淘宝", rows[0].title)
    }

    /** 行里混着网址时，网址那一段不能被当成密码。 */
    @Test
    fun `一行里带网址时不会把网址当密码`() {
        val r = CredentialParser.parse("admin abc123 www.taobao.com")!!
        assertEquals("admin", r.username)
        assertEquals("abc123", r.password)
        assertEquals("www.taobao.com", r.url)
    }

    /** 平台名和账号贴在一起：不能把整串 `抖音12313213` 当账号。 */
    @Test
    fun `平台名和账号贴在一起时拆开`() {
        val r = CredentialParser.parse("抖音12313213 1231313")!!
        assertEquals("抖音", r.title)
        assertEquals("12313213", r.username)
        assertEquals("1231313", r.password)
    }

    /** 只有两段、且第一段是纯中文平台名时，第一段是标题不是账号。 */
    @Test
    fun `两段里第一段是平台名时当标题`() {
        val r = CredentialParser.parse("抖音 12313213")!!
        assertEquals("抖音", r.title)
        assertEquals("12313213", r.username)
        assertNull(r.password)
    }

    /** 两段都是普通值时仍按「账号 密码」理解。 */
    @Test
    fun `两段都不是平台名时仍当账号密码`() {
        val r = CredentialParser.parse("admin 123456")!!
        assertNull(r.title)
        assertEquals("admin", r.username)
        assertEquals("123456", r.password)
    }

    /** 认反了的时候，一键对调。 */
    @Test
    fun `swapped 把账号密码对调`() {
        val r = CredentialParser.Result(username = "抖音", password = "abc123", url = "a.com", title = "标题")
        val s = r.swapped()
        assertEquals("abc123", s.username)
        assertEquals("抖音", s.password)
        assertEquals("a.com", s.url)
        assertEquals("标题", s.title)
    }

    /** 只有一边有值时，对调不会凭空造出另一个。 */
    @Test
    fun `swapped 对单边为空也安全`() {
        val s = CredentialParser.Result(username = "only").swapped()
        assertNull(s.username)
        assertEquals("only", s.password)
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
