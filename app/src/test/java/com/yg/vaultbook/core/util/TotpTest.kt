package com.yg.vaultbook.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TOTP 的测试对着 RFC 6238 附录 B 的官方向量逐条比对。
 *
 * 这些向量为什么值得一条条写死：TOTP 写错了不会抛异常，只会**给出一个格式正确、
 * 但服务器不认的六位数**。用户看到的表现是"验证码一直错"，而排查时无从下手 ——
 * 所以必须有一个外部权威答案来钉住实现。
 *
 * 官方向量的密钥是 ASCII "12345678901234567890"（20 字节）重复填充到
 * 32 / 64 字节用于 SHA256 / SHA512。
 */
class TotpTest {

    private fun sha1Secret() = "12345678901234567890".toByteArray(Charsets.US_ASCII)

    private fun sha256Secret() =
        "12345678901234567890123456789012".toByteArray(Charsets.US_ASCII)

    /**
     * RFC 6238 附录 B 的 SHA512 密钥是 64 字节：把 "12345678901234567890"（20 字节）
     * 重复到 64 字节，即三遍完整 + 前四位 "1234"。
     * 这里踩过一次坑：误用 32 字节的串拼两遍，SHA1/SHA256 全绿、只有 SHA512 对不上。
     */
    private fun sha512Secret() =
        ("1234567890123456789012345678901234567890" +
            "123456789012345678901234").toByteArray(Charsets.US_ASCII)

    private fun config8(secret: ByteArray, algorithm: Totp.Algorithm) =
        Totp.Config(secret = secret, digits = 8, periodSeconds = 30, algorithm = algorithm)

    // ── RFC 6238 附录 B ────────────────────────────────────────────────

    @Test
    fun `RFC6238 SHA1 向量 T=59`() {
        val cfg = config8(sha1Secret(), Totp.Algorithm.SHA1)
        assertEquals("94287082", Totp.codeAt(cfg, 59L))
    }

    @Test
    fun `RFC6238 SHA1 向量 T=1111111109`() {
        val cfg = config8(sha1Secret(), Totp.Algorithm.SHA1)
        assertEquals("07081804", Totp.codeAt(cfg, 1_111_111_109L))
    }

    @Test
    fun `RFC6238 SHA1 向量 T=1111111111`() {
        val cfg = config8(sha1Secret(), Totp.Algorithm.SHA1)
        assertEquals("14050471", Totp.codeAt(cfg, 1_111_111_111L))
    }

    @Test
    fun `RFC6238 SHA1 向量 T=1234567890`() {
        val cfg = config8(sha1Secret(), Totp.Algorithm.SHA1)
        assertEquals("89005924", Totp.codeAt(cfg, 1_234_567_890L))
    }

    @Test
    fun `RFC6238 SHA1 向量 T=2000000000`() {
        val cfg = config8(sha1Secret(), Totp.Algorithm.SHA1)
        assertEquals("69279037", Totp.codeAt(cfg, 2_000_000_000L))
    }

    @Test
    fun `RFC6238 SHA1 向量 T=20000000000`() {
        val cfg = config8(sha1Secret(), Totp.Algorithm.SHA1)
        assertEquals("65353130", Totp.codeAt(cfg, 20_000_000_000L))
    }

    @Test
    fun `RFC6238 SHA256 向量 T=59`() {
        val cfg = config8(sha256Secret(), Totp.Algorithm.SHA256)
        assertEquals("46119246", Totp.codeAt(cfg, 59L))
    }

    @Test
    fun `RFC6238 SHA256 向量 T=1234567890`() {
        val cfg = config8(sha256Secret(), Totp.Algorithm.SHA256)
        assertEquals("91819424", Totp.codeAt(cfg, 1_234_567_890L))
    }

    @Test
    fun `RFC6238 SHA512 向量 T=59`() {
        val cfg = config8(sha512Secret(), Totp.Algorithm.SHA512)
        assertEquals("90693936", Totp.codeAt(cfg, 59L))
    }

    @Test
    fun `RFC6238 SHA512 向量 T=1234567890`() {
        val cfg = config8(sha512Secret(), Totp.Algorithm.SHA512)
        assertEquals("93441116", Totp.codeAt(cfg, 1_234_567_890L))
    }

    // ── 位数与格式 ─────────────────────────────────────────────────────

    @Test
    fun `六位码是八位码的后六位`() {
        val secret = sha1Secret()
        val eight = Totp.codeAt(config8(secret, Totp.Algorithm.SHA1), 59L)
        val six = Totp.codeAt(
            Totp.Config(secret = secret, digits = 6),
            59L
        )
        assertEquals("287082", six)
        assertEquals(eight.takeLast(6), six)
    }

    @Test
    fun `不足位数时左侧补零`() {
        // T=1111111109 的 SHA1 向量是 07081804，六位应为 081804（首位的 0 不能被吃掉）
        val cfg = Totp.Config(secret = sha1Secret(), digits = 6)
        assertEquals("081804", Totp.codeAt(cfg, 1_111_111_109L))
    }

    @Test
    fun `format 把六位分成两段`() {
        assertEquals("123 456", Totp.format("123456"))
        assertEquals("1234 5678", Totp.format("12345678"))
        assertEquals("12", Totp.format("12"))
    }

    // ── 计数器与剩余秒数 ───────────────────────────────────────────────

    @Test
    fun `counterAt 按周期下取整`() {
        assertEquals(0L, Totp.counterAt(30, 0L))
        assertEquals(0L, Totp.counterAt(30, 29_999L))
        assertEquals(1L, Totp.counterAt(30, 30_000L))
        assertEquals(1L, Totp.counterAt(30, 59_999L))
        assertEquals(2L, Totp.counterAt(30, 60_000L))
    }

    @Test
    fun `remainingSeconds 在周期边界上是满值`() {
        assertEquals(30, Totp.remainingSeconds(30, 0L))
        assertEquals(30, Totp.remainingSeconds(30, 30_000L))
    }

    @Test
    fun `remainingSeconds 在周期末尾是一秒`() {
        assertEquals(1, Totp.remainingSeconds(30, 29_000L))
        assertEquals(1, Totp.remainingSeconds(30, 29_999L))
    }

    @Test
    fun `millis 版本与秒版本一致`() {
        val cfg = Totp.Config(secret = sha1Secret(), digits = 8)
        assertEquals(
            Totp.codeAt(cfg, 59L),
            Totp.code(cfg, 59_000L)
        )
    }

    // ── Base32 ─────────────────────────────────────────────────────────

    @Test
    fun `解码标准向量得到 ASCII 数字串`() {
        val decoded = Totp.parseBase32("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
        assertNotNull(decoded)
        assertEquals("12345678901234567890", String(decoded!!, Charsets.US_ASCII))
    }

    @Test
    fun `解码常见示例 JBSWY3DPEHPK3PXP`() {
        val decoded = Totp.parseBase32("JBSWY3DPEHPK3PXP")!!
        val expected = byteArrayOf(
            'H'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(), 'l'.code.toByte(),
            'o'.code.toByte(), '!'.code.toByte(),
            0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()
        )
        assertTrue(expected.contentEquals(decoded))
    }

    @Test
    fun `小写 空格 连字符 与填充都被忽略`() {
        val canonical = Totp.parseBase32("JBSWY3DPEHPK3PXP")!!
        val messy = Totp.parseBase32("jbsw y3dp-ehpk 3pxp====")!!
        assertTrue(canonical.contentEquals(messy))
    }

    @Test
    fun `两字符解出一个字节`() {
        val decoded = Totp.parseBase32("MY")!!
        assertEquals(1, decoded.size)
        assertEquals(0x66, decoded[0].toInt() and 0xFF)
    }

    @Test
    fun `非法字符返回 null`() {
        assertNull(Totp.parseBase32("JBSWY3DP1HPK3PXP"))  // 1 不在 Base32 字母表
        assertNull(Totp.parseBase32("ABC!"))
    }

    @Test
    fun `空输入返回 null`() {
        assertNull(Totp.parseBase32(null))
        assertNull(Totp.parseBase32(""))
        assertNull(Totp.parseBase32("   "))
        assertNull(Totp.parseBase32("===="))
    }

    // ── otpauth URI ────────────────────────────────────────────────────

    @Test
    fun `解析标准 otpauth URI`() {
        val parsed = Totp.parseOtpAuthUri(
            "otpauth://totp/ACME%20Co:john@example.com" +
                "?secret=JBSWY3DPEHPK3PXP&issuer=ACME%20Co&algorithm=SHA1&digits=6&period=30"
        )
        assertNotNull(parsed)
        assertEquals("JBSWY3DPEHPK3PXP", parsed!!.secretBase32)
        assertEquals("ACME Co", parsed.issuer)
        assertEquals("john@example.com", parsed.account)
        assertEquals(6, parsed.digits)
        assertEquals(30, parsed.periodSeconds)
        assertEquals(Totp.Algorithm.SHA1, parsed.algorithm)
    }

    @Test
    fun `解析 SHA256 与 8 位 60 秒的 URI`() {
        val parsed = Totp.parseOtpAuthUri(
            "otpauth://totp/Bank:alice?secret=JBSWY3DPEHPK3PXP&algorithm=SHA256&digits=8&period=60"
        )!!
        assertEquals(Totp.Algorithm.SHA256, parsed.algorithm)
        assertEquals(8, parsed.digits)
        assertEquals(60, parsed.periodSeconds)
        assertEquals("Bank", parsed.issuer)
    }

    @Test
    fun `label 里没有冒号时 issuer 取 query 参数`() {
        val parsed = Totp.parseOtpAuthUri(
            "otpauth://totp/alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=GitHub"
        )!!
        assertEquals("GitHub", parsed.issuer)
        assertEquals("alice@example.com", parsed.account)
    }

    @Test
    fun `缺 secret 返回 null`() {
        assertNull(Totp.parseOtpAuthUri("otpauth://totp/ACME:john?issuer=ACME"))
    }

    @Test
    fun `hotp 返回 null`() {
        assertNull(Totp.parseOtpAuthUri("otpauth://hotp/ACME:john?secret=JBSWY3DPEHPK3PXP"))
    }

    @Test
    fun `非 otpauth 协议返回 null`() {
        assertNull(Totp.parseOtpAuthUri("https://example.com/?secret=JBSWY3DPEHPK3PXP"))
        assertNull(Totp.parseOtpAuthUri(null))
        assertNull(Totp.parseOtpAuthUri(""))
    }

    @Test
    fun `非法的 digits 与 period 回落到默认值`() {
        val parsed = Totp.parseOtpAuthUri(
            "otpauth://totp/A:b?secret=JBSWY3DPEHPK3PXP&digits=99&period=0"
        )!!
        assertEquals(Totp.DEFAULT_DIGITS, parsed.digits)
        assertEquals(Totp.DEFAULT_PERIOD_SECONDS, parsed.periodSeconds)
    }

    @Test
    fun `未知 algorithm 回落到 SHA1`() {
        val parsed = Totp.parseOtpAuthUri(
            "otpauth://totp/A:b?secret=JBSWY3DPEHPK3PXP&algorithm=MD5"
        )!!
        assertEquals(Totp.Algorithm.SHA1, parsed.algorithm)
    }

    // ── Config 校验 ────────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `空密钥被拒绝`() {
        Totp.Config(secret = ByteArray(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `位数超出范围被拒绝`() {
        Totp.Config(secret = sha1Secret(), digits = 5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `周期为零被拒绝`() {
        Totp.Config(secret = sha1Secret(), periodSeconds = 0)
    }
}
