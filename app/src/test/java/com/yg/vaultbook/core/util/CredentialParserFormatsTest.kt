package com.yg.vaultbook.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 粘贴识别的"多格式自测"。
 *
 * 用户的原话是「你应该搞多种格式来自测」—— 之前每次都是他贴一段认不出来，
 * 我们补一条规则，下一段又是新的形状。这个文件把真实世界里会碰到的写法
 * 各摆一份，规则一改就跑，防止"修好一个坏掉两个"。
 *
 * 所有样本里的账号密码都是编的。
 */
class CredentialParserFormatsTest {

    private fun one(raw: String): CredentialParser.Result {
        val r = CredentialParser.parse(raw)
        assertNotNull("应该认出一条：$raw", r)
        return r!!
    }

    // ── 1. 一行一个「标签:账号:密码」 ────────────────────────────

    @Test
    fun `三行双冒号各自成条`() {
        val text = "微信:demo_wx_001:Wx@123456\n" +
            "邮箱:demo_mail@example.com:Mail#2024\n" +
            "手机:13800000000:Phone\$888"
        val rows = CredentialParser.parseMany(text)
        assertEquals(3, rows.size)

        assertEquals("微信", rows[0].title)
        assertEquals("demo_wx_001", rows[0].username)
        assertEquals("Wx@123456", rows[0].password)

        // 头是「邮箱」这种字段名，但它同时是平台名，留着当标题
        assertEquals("邮箱", rows[1].title)
        assertEquals("demo_mail@example.com", rows[1].username)
        assertEquals("Mail#2024", rows[1].password)

        assertEquals("手机", rows[2].title)
        assertEquals("13800000000", rows[2].username)
        assertEquals("Phone\$888", rows[2].password)
    }

    @Test
    fun `多行双冒号时单条入口取第一条`() {
        val text = "微信:demo_wx_001:Wx@123456\n邮箱:demo_mail@example.com:Mail#2024"
        val r = one(text)
        assertEquals("微信", r.title)
        assertEquals("demo_wx_001", r.username)
    }

    @Test
    fun `通用字段名不当标题`() {
        val r = one("账号:demo_admin:Adm@2024")
        assertNull(r.title)
        assertEquals("demo_admin", r.username)
        assertEquals("Adm@2024", r.password)
    }

    @Test
    fun `密码里带冒号时整段保留`() {
        val r = one("路由:demo_router:Pa:ss:2024")
        assertEquals("路由", r.title)
        assertEquals("demo_router", r.username)
        assertEquals("Pa:ss:2024", r.password)
    }

    // ── 2. CSV / 制表符 / 分号表格 ──────────────────────────────

    @Test
    fun `CSV 带表头带备注`() {
        val text = "平台,账号,密码,备注\n" +
            "GitHub,demo_gh,Gh@123456,开发测试\n" +
            "Gmail,demo.gmail@example.com,Gm#654321,邮箱测试"
        val rows = CredentialParser.parseMany(text)
        assertEquals(2, rows.size)

        assertEquals("GitHub", rows[0].title)
        assertEquals("demo_gh", rows[0].username)
        assertEquals("Gh@123456", rows[0].password)
        assertEquals("开发测试", rows[0].notes)

        assertEquals("Gmail", rows[1].title)
        assertEquals("demo.gmail@example.com", rows[1].username)
        assertEquals("Gm#654321", rows[1].password)
        assertEquals("邮箱测试", rows[1].notes)
    }

    @Test
    fun `制表符表格`() {
        val rows = CredentialParser.parseMany("平台\t账号\t密码\n淘宝\tdemo_tb\tTb@123456")
        assertEquals(1, rows.size)
        assertEquals("淘宝", rows[0].title)
        assertEquals("demo_tb", rows[0].username)
        assertEquals("Tb@123456", rows[0].password)
    }

    @Test
    fun `分号表格`() {
        val rows = CredentialParser.parseMany("平台;账号;密码\n淘宝;demo_tb;Tb@123456")
        assertEquals(1, rows.size)
        assertEquals("淘宝", rows[0].title)
        assertEquals("demo_tb", rows[0].username)
    }

    @Test
    fun `竖线表格`() {
        val rows = CredentialParser.parseMany("平台|账号|密码\n知乎|demo_zh|Zh@123456")
        assertEquals(1, rows.size)
        assertEquals("知乎", rows[0].title)
        assertEquals("demo_zh", rows[0].username)
        assertEquals("Zh@123456", rows[0].password)
    }

    @Test
    fun `英文表头也认`() {
        val rows = CredentialParser.parseMany("title,username,password\nSteam,demo_st,Demo@2024")
        assertEquals(1, rows.size)
        assertEquals("Steam", rows[0].title)
        assertEquals("demo_st", rows[0].username)
        assertEquals("Demo@2024", rows[0].password)
    }

    @Test
    fun `没有账号列的表不当表格`() {
        // 只有两列、且都不是账号密码 → 表格规则不该开工，
        // 至少不能把表头（「项目」「说明」）当成一条记录的字段。
        val rows = CredentialParser.parseMany("项目,说明\n淘宝,购物用")
        assertTrue(
            "表头不该变成记录",
            rows.none { it.title == "项目" || it.username == "项目" || it.notes == "说明" }
        )
    }

    // ── 3. JSON 数组 ────────────────────────────────────────────

    @Test
    fun `JSON 数组两条`() {
        val text = "[{\"platform\":\"微博\",\"account\":\"weibo_demo\",\"password\":\"Wb@123456\"}," +
            "{\"platform\":\"QQ\",\"account\":\"1000000001\",\"password\":\"Qq#654321\"}]"
        val rows = CredentialParser.parseMany(text)
        assertEquals(2, rows.size)

        assertEquals("微博", rows[0].title)
        assertEquals("weibo_demo", rows[0].username)
        assertEquals("Wb@123456", rows[0].password)

        assertEquals("QQ", rows[1].title)
        assertEquals("1000000001", rows[1].username)
        assertEquals("Qq#654321", rows[1].password)
    }

    @Test
    fun `JSON 单对象`() {
        val r = one("{\"site\":\"淘宝\",\"user\":\"demo_tb\",\"pwd\":\"Tb@123456\",\"note\":\"主号\"}")
        assertEquals("淘宝", r.title)
        assertEquals("demo_tb", r.username)
        assertEquals("Tb@123456", r.password)
        assertEquals("主号", r.notes)
    }

    @Test
    fun `JSON 里的转义还原`() {
        val r = one("{\"platform\":\"测试\",\"account\":\"demo_a\",\"password\":\"Pa\\\"ss\\\\01\"}")
        assertEquals("demo_a", r.username)
        assertEquals("Pa\"ss\\01", r.password)
    }

    @Test
    fun `JSON 数字密码不当字符串丢`() {
        val r = one("{\"platform\":\"门禁\",\"account\":\"demo_gate\",\"password\":20240917}")
        assertEquals("20240917", r.password)
    }

    // ── 4. 备注 ────────────────────────────────────────────────

    @Test
    fun `备注不再被当字段名丢掉`() {
        val r = one("淘宝\n账号：demo_tb\n密码：Tb@123456\n备注：备用密码在邮箱里")
        assertEquals("淘宝", r.title)
        assertEquals("demo_tb", r.username)
        assertEquals("Tb@123456", r.password)
        assertEquals("备用密码在邮箱里", r.notes)
    }

    @Test
    fun `备注在冒号写法里也留`() {
        val r = one("标题：知乎\n账号：demo_zh\n密码：Zh@123456\n说明：二级验证开在旧手机")
        assertEquals("知乎", r.title)
        assertEquals("二级验证开在旧手机", r.notes)
    }

    // ── 5. 链接 userinfo ───────────────────────────────────────

    @Test
    fun `https 里的 user 和 pass`() {
        val r = one("https://demo_user:Demo%40123@example.com/login")
        assertEquals("demo_user", r.username)
        assertEquals("Demo@123", r.password) // %40 还原成 @
        assertEquals("example.com", r.url)
    }

    @Test
    fun `ftp 里的 user 和 pass`() {
        val r = one("ftp://ftp_demo:Ftp#2024@example.com/")
        assertEquals("ftp_demo", r.username)
        assertEquals("Ftp#2024", r.password)
    }

    @Test
    fun `几条链接各成一条`() {
        val rows = CredentialParser.parseMany(
            "https://demo_user:Demo%40123@example.com/login\n" +
                "ftp://ftp_demo:Ftp#2024@example.com/"
        )
        assertEquals(2, rows.size)
        assertEquals("demo_user", rows[0].username)
        assertEquals("ftp_demo", rows[1].username)
    }

    @Test
    fun `邮箱不会被当成站点`() {
        val r = one("账号：demo.gmail@example.com\n密码：Gm#654321")
        assertEquals("demo.gmail@example.com", r.username)
        assertNull(r.url)
    }

    // ── 6. 老形状要一直好用 ─────────────────────────────────────

    @Test
    fun `两段平台名加账号`() {
        val r = one("抖音 12313213 1231313")
        assertEquals("抖音", r.title)
        assertEquals("12313213", r.username)
        assertEquals("1231313", r.password)
    }

    @Test
    fun `平台名和账号贴在一起`() {
        val r = one("抖音12313213 1231313")
        assertEquals("抖音", r.title)
        assertEquals("12313213", r.username)
        assertEquals("1231313", r.password)
    }

    @Test
    fun `空白分隔的字段名`() {
        val r = one("账号 demo_gh 密码 Gh@123456")
        assertEquals("demo_gh", r.username)
        assertEquals("Gh@123456", r.password)
    }

    @Test
    fun `行首序号被剥掉`() {
        val r = one("1. 淘宝\ndemo_tb\nTb@123456")
        assertEquals("淘宝", r.title)
        assertEquals("demo_tb", r.username)
        assertEquals("Tb@123456", r.password)
    }

    @Test
    fun `整行横线当分隔`() {
        val rows = CredentialParser.parseMany("淘宝 demo_tb Tb@123456\n-----------\n京东 demo_jd Jd@654321")
        assertEquals(2, rows.size)
        assertEquals("淘宝", rows[0].title)
        assertEquals("京东", rows[1].title)
    }

    @Test
    fun `纯网址不算凭据`() {
        // 只认出网址时不弹「检测到账号密码」：Result 可以有 url，但没有凭据
        val r = CredentialParser.parse("https://www.example.com/index.html")
        assertTrue(r == null || !r.hasCredential)
    }

    @Test
    fun `纯标点不算凭据`() {
        assertNull(CredentialParser.parse("、、；！！"))
    }

    @Test
    fun `备注单独存在也算有内容`() {
        // 认不出账号密码但认出了备注：不该整条丢掉，也不该当成凭据
        val r = CredentialParser.parse("备注：周五前改掉旧密码")
        assertNull(r?.password)
    }
}
