package com.yg.vaultbook.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表格导入解析的测试。
 *
 * 重点不在"能不能解析标准 CSV"，而在用户手里那些**不标准**的表：
 * Excel 中文版导出的 GBK、带 BOM 的、用制表符的、备注里带逗号换行的、
 * 只有两列（平台 + 密码）的。每一条都对应一次真实的"导入完发现密码是乱码"。
 */
class CsvImportTest {

    private fun parse(text: String) = CsvImport.parse(text.toByteArray(Charsets.UTF_8))

    private fun pwd(row: CsvRow): String? = row.password?.concatToString()

    // ── 表头识别 ──────────────────────────────────────────────────

    @Test
    fun `中文表头按列名映射`() {
        val r = parse(
            """
            平台,账号,密码,网址,备注
            支付宝,13800138000,abc123,https://alipay.com,备用
            """.trimIndent()
        )
        assertTrue(r.hasHeader)
        assertEquals(1, r.rowCount)
        val row = r.rows[0]
        assertEquals("支付宝", row.title)
        assertEquals("13800138000", row.username)
        assertEquals("abc123", pwd(row))
        assertEquals("https://alipay.com", row.url)
        assertEquals("备用", row.notes)
    }

    @Test
    fun `英文表头也能识别`() {
        val r = parse("Title,Username,Password,URL,Notes,Category,TOTP")
        assertTrue(r.hasHeader)
        assertEquals(listOf("Title", "Username", "Password", "URL", "Notes", "Category", "TOTP"), r.columns)
    }

    @Test
    fun `表头顺序与常见软件不同也能对位`() {
        val r = parse(
            """
            密码,平台,账号
            xyz789,微信,weixin_user
            """.trimIndent()
        )
        assertTrue(r.hasHeader)
        val row = r.rows[0]
        assertEquals("微信", row.title)
        assertEquals("weixin_user", row.username)
        assertEquals("xyz789", pwd(row))
    }

    @Test
    fun `只命中密码一列不被当成表头`() {
        // 第一格真的写着"密码"两个字，这是数据不是表头
        val r = parse(
            """
            密码,abc123
            银行,def456
            """.trimIndent()
        )
        assertEquals(2, r.rowCount)
        assertEquals("密码", r.rows[0].title)
    }

    // ── 无表头时按列序 ────────────────────────────────────────────

    @Test
    fun `两列按平台加密码解释`() {
        val r = parse(
            """
            淘宝,taobao123
            京东,jd456
            """.trimIndent()
        )
        assertEquals(2, r.rowCount)
        assertEquals("淘宝", r.rows[0].title)
        assertNull(r.rows[0].username)
        assertEquals("taobao123", pwd(r.rows[0]))
    }

    @Test
    fun `三列按平台账号密码解释`() {
        val r = parse("知乎,me@zhihu.com,pass123")
        assertEquals(1, r.rowCount)
        assertEquals("me@zhihu.com", r.rows[0].username)
        assertEquals("pass123", pwd(r.rows[0]))
    }

    // ── 引号与特殊字符 ────────────────────────────────────────────

    @Test
    fun `引号内的逗号与换行不被拆开`() {
        val r = parse(
            "平台,密码,备注\n" +
                "\"淘宝\",\"a,b\",\"第一行\n第二行\"\n"
        )
        assertEquals(1, r.rowCount)
        assertEquals("a,b", pwd(r.rows[0]))
        assertEquals("第一行\n第二行", r.rows[0].notes)
    }

    @Test
    fun `两个连续引号是一条引号`() {
        val r = parse("平台,密码,备注\n米游社,abc,\"他说\"\"好\"\"\"")
        assertEquals("他说\"好\"", r.rows[0].notes)
    }

    @Test
    fun `分隔符按内容自动判定`() {
        val tab = parse("平台\t密码\nB站\tabc")
        assertEquals('\t', tab.delimiter)
        assertEquals("B站", tab.rows[0].title)

        val semi = parse("平台;密码\nB站;abc")
        assertEquals(';', semi.delimiter)
        assertEquals("abc", pwd(semi.rows[0]))
    }

    // ── 编码 ──────────────────────────────────────────────────────

    @Test
    fun `GBK 文件不会变成乱码`() {
        val bytes = "平台,密码\n网易云音乐,abc123".toByteArray(charset("GBK"))
        val r = CsvImport.parse(bytes)
        assertEquals("GBK", r.encoding)
        assertEquals("网易云音乐", r.rows[0].title)
    }

    @Test
    fun `带 BOM 的 UTF8 文件不会多出一个字符`() {
        val raw = "平台,密码\n知乎,abc".toByteArray(Charsets.UTF_8)
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + raw
        val r = CsvImport.parse(withBom)
        assertEquals("UTF-8 BOM", r.encoding)
        // BOM 已被吃掉，表头第一格就是干净的「平台」，没变成 "?"+"平台"
        assertEquals("平台", r.columns[0])
        assertEquals("知乎", r.rows[0].title)
    }

    // ── 边界 ──────────────────────────────────────────────────────

    @Test
    fun `空行被跳过并计数`() {
        val r = parse("平台,密码\n淘宝,a\n\n\n京东,b\n")
        assertEquals(2, r.rowCount)
        assertEquals(2, r.skippedLines)
    }

    @Test
    fun `全空文件返回零行不抛异常`() {
        val r = parse("   \n\n  ")
        assertEquals(0, r.rowCount)
        assertTrue(r.columns.isEmpty())
    }

    @Test
    fun `密码列缺失时条目仍然建立`() {
        val r = parse("平台,密码\n淘宝\n京东,b")
        assertEquals(2, r.rowCount)
        assertNull(pwd(r.rows[0]))
        assertEquals("b", pwd(r.rows[1]))
    }

    @Test
    fun `标题为空时回退到网址`() {
        val r = parse("平台,账号,密码,网址\n,me,abc,https://example.com")
        assertEquals("https://example.com", r.rows[0].title)
    }

    @Test
    fun `clearSecrets 之后密码被抹掉`() {
        val r = parse("平台,密码\n淘宝,secret")
        val before = pwd(r.rows[0])
        assertEquals("secret", before)
        r.clearSecrets()
        assertArrayEquals(CharArray(6), r.rows[0].password)
    }
}
