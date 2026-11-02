package com.yg.vaultbook.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "程序生成"的形状：导出文件里的 JSON / XML、抓包得到的查询串、
 * 括号包值、账号密码上下交替、同一段里混着几种分隔符。
 *
 * 这些形状和"人手抄"的不同：字段名是显式的，值被引号、标签、括号包着，
 * 交给按位置猜的规则只会得到半截 —— `账号(xx)` 会连标签一起进用户名，
 * `Wb@123456&note=…` 会连下一个键一起进密码。所以每一种都单独有规则，
 * 这个文件就是它们的对照表（样本里的账号密码全是编的）。
 */
class CredentialParserStructuredTest {

    // ── 1. 外层包着数组的 JSON ──────────────────────────────────

    @Test
    fun `外层包着 accounts 数组的 JSON 认两条`() {
        val text = """
            {
              "accounts": [
                {"site": "Steam", "user": "demo_steam01", "pass": "St@123456", "note": "游戏"},
                {"site": "Epic", "user": "demo_epic@example.com", "pass": "Ep#654321", "note": "测试"}
              ]
            }
        """.trimIndent()

        val rows = CredentialParser.parseMany(text)
        assertEquals(2, rows.size)
        assertEquals("Steam", rows[0].title)
        assertEquals("demo_steam01", rows[0].username)
        assertEquals("St@123456", rows[0].password)
        assertEquals("游戏", rows[0].notes)
        assertEquals("Epic", rows[1].title)
        assertEquals("demo_epic@example.com", rows[1].username)
        assertEquals("Ep#654321", rows[1].password)
    }

    @Test
    fun `裸 pass 键就是密码`() {
        val r = CredentialParser.parse("""{"site":"微博","user":"demo_wb","pass":"Wb@123456"}""")
        assertEquals("微博", r?.title)
        assertEquals("demo_wb", r?.username)
        assertEquals("Wb@123456", r?.password)
    }

    // ── 2. 浅层 XML ─────────────────────────────────────────────

    @Test
    fun `浅层 XML 认两条`() {
        val text = """
            <accounts>
              <account>
                <platform>微信</platform>
                <user>wx_xml_demo</user>
                <pass>Wx@123456</pass>
              </account>
              <account>
                <platform>QQ</platform>
                <user>1000000003</user>
                <pass>Qq#654321</pass>
              </account>
            </accounts>
        """.trimIndent()

        val rows = CredentialParser.parseMany(text)
        assertEquals(2, rows.size)
        assertEquals("微信", rows[0].title)
        assertEquals("wx_xml_demo", rows[0].username)
        assertEquals("Wx@123456", rows[0].password)
        assertEquals("QQ", rows[1].title)
        assertEquals("1000000003", rows[1].username)
        assertEquals("Qq#654321", rows[1].password)
    }

    @Test
    fun `XML 里的备注与网址也认`() {
        val text = """
            <item><platform>知乎</platform><url>https://www.zhihu.com/</url>
            <user>zhihu_xml</user><pass>Zh#654321</pass><note>备用</note></item>
        """.trimIndent()

        val r = CredentialParser.parse(text)
        assertEquals("知乎", r?.title)
        assertEquals("zhihu_xml", r?.username)
        assertEquals("Zh#654321", r?.password)
        assertEquals("www.zhihu.com", r?.url)
        assertEquals("备用", r?.notes)
    }

    // ── 3. 括号包值 ─────────────────────────────────────────────

    @Test
    fun `括号包值时标签不进值`() {
        val text = "平台(淘宝) 账号(taobao_bracket) 密码(Tb@123456)\n" +
            "平台(京东) 账号(jd_bracket@example.com) 密码(Jd#654321)"

        val rows = CredentialParser.parseMany(text)
        assertEquals(2, rows.size)
        assertEquals("淘宝", rows[0].title)
        assertEquals("taobao_bracket", rows[0].username)
        assertEquals("Tb@123456", rows[0].password)
        assertEquals("京东", rows[1].title)
        assertEquals("jd_bracket@example.com", rows[1].username)
        assertEquals("Jd#654321", rows[1].password)
    }

    @Test
    fun `全角括号一样认`() {
        val r = CredentialParser.parse("平台（微博）账号（weibo_full）密码（Wb@123456）")
        assertEquals("微博", r?.title)
        assertEquals("weibo_full", r?.username)
        assertEquals("Wb@123456", r?.password)
    }

    @Test
    fun `括号里只有列名说明时不认`() {
        assertNull(CredentialParser.parse("平台(必填) 账号(必填)"))
    }

    // ── 4. 查询串 ───────────────────────────────────────────────

    @Test
    fun `查询串里的 amp 不进密码`() {
        val text = "platform=微博&account=weibo_param&password=Wb@123456&note=参数测试\n" +
            "platform=知乎&account=zhihu_param&password=Zh#654321"

        val rows = CredentialParser.parseMany(text)
        assertEquals(2, rows.size)
        assertEquals("微博", rows[0].title)
        assertEquals("weibo_param", rows[0].username)
        assertEquals("Wb@123456", rows[0].password)
        assertEquals("参数测试", rows[0].notes)
        assertEquals("知乎", rows[1].title)
        assertEquals("zhihu_param", rows[1].username)
        assertEquals("Zh#654321", rows[1].password)
        assertTrue(rows.none { it.password?.contains('&') == true })
    }

    // ── 5. 账号密码上下交替 ─────────────────────────────────────

    @Test
    fun `账号密码上下交替时两行一组`() {
        val text = "demo_mail01@example.com\nMail@123456\n" +
            "demo_mail02@example.com\nMail#654321"

        val rows = CredentialParser.parseMany(text)
        assertEquals(2, rows.size)
        assertEquals("demo_mail01@example.com", rows[0].username)
        assertEquals("Mail@123456", rows[0].password)
        assertEquals("demo_mail02@example.com", rows[1].username)
        assertEquals("Mail#654321", rows[1].password)
    }

    @Test
    fun `三行交替不硬凑`() {
        val text = "demo_mail01@example.com\nMail@123456\ndemo_mail02@example.com"
        assertEquals(1, CredentialParser.parseMany(text).size)
    }

    // ── 6. 同一段里混着几种分隔符 ───────────────────────────────

    @Test
    fun `同行混用分号竖线逗号认三条`() {
        val text = "平台=微博;账号=weibo_mix;密码=Wb@123456;备注=分号\n" +
            "平台|知乎|zhihu_mix|Zh#654321|竖线\n" +
            "平台, B站, bili_mix, Bili@123456, 逗号"

        val rows = CredentialParser.parseMany(text)
        assertEquals(3, rows.size)

        assertEquals("微博", rows[0].title)
        assertEquals("weibo_mix", rows[0].username)
        assertEquals("Wb@123456", rows[0].password)
        assertEquals("分号", rows[0].notes)

        assertEquals("知乎", rows[1].title)
        assertEquals("zhihu_mix", rows[1].username)
        assertEquals("Zh#654321", rows[1].password)
        assertEquals("竖线", rows[1].notes)

        assertEquals("B站", rows[2].title)
        assertEquals("bili_mix", rows[2].username)
        assertEquals("Bili@123456", rows[2].password)
        assertEquals("逗号", rows[2].notes)
    }

    // ── 别把老形状弄坏 ─────────────────────────────────────────

    @Test
    fun `表头行不单独成记录`() {
        assertNull(CredentialParser.parse("平台,账号,密码"))
    }

    @Test
    fun `带表头的表格还是照旧`() {
        val text = "平台,账号,密码\n淘宝,taobao_old,Tb@123456\n京东,jd_old,Jd#654321"
        val rows = CredentialParser.parseMany(text)
        assertEquals(2, rows.size)
        assertEquals("taobao_old", rows[0].username)
        assertEquals("Tb@123456", rows[0].password)
    }
}
