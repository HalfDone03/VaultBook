package com.yg.vaultbook.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

/**
 * 密码强度评估的测试。
 *
 * 刻意用**字符集熵**而不是三段式打勾法，所以这里的用例重点在
 * "看起来复杂但其实很弱"（`P@ssw0rd`）和"看起来普通但其实很强"（长句）。
 */
class PasswordStrengthTest {

    private val ln2 = ln(2.0)

    private fun bits(chars: String): Double = PasswordStrength.entropyBits(chars.toCharArray())

    private fun score(chars: String): Int = PasswordStrength.score(chars.toCharArray())

    // ── entropyBits ────────────────────────────────────────────────

    @Test
    fun `空密码熵为零`() {
        assertEquals(0.0, bits(""), 0.0)
    }

    @Test
    fun `纯小写的熵是 长度乘 log2_26`() {
        // 8 * log2(26) ≈ 37.6
        assertEquals(8 * (ln(26.0) / ln2), bits("abcdefgh"), 1e-9)
    }

    @Test
    fun `四类字符齐全时字符池为 95`() {
        // 26 + 26 + 10 + 33 = 95
        assertEquals(12 * (ln(95.0) / ln2), bits("Abcdef1!@#$%"), 1e-9)
    }

    @Test
    fun `中文字符按 100 的池粗估`() {
        // 不低于同长度的纯小写，但也不高估
        val cjk = bits("密码密码密码")
        val lower = bits("abcdef")
        assertTrue(cjk >= lower)
    }

    // ── score：弱密码必须被判弱 ────────────────────────────────────

    @Test
    fun `空密码零分`() {
        assertEquals(0, score(""))
    }

    @Test
    fun `常见口令直接零分`() {
        assertEquals(0, score("123456"))
        assertEquals(0, score("password"))
        assertEquals(0, score("qwerty"))
        assertEquals(0, score("woaini"))
        assertEquals(0, score("1314520"))
    }

    @Test
    fun `常见口令大小写不敏感`() {
        assertEquals(0, score("PassWord"))
        assertEquals(0, score("QWERTY"))
    }

    @Test
    fun `单字符重复零分`() {
        assertEquals(0, score("aaaaaaaaaaaaaaa"))
        assertEquals(0, score("1111"))
    }

    @Test
    fun `键盘序列降两级`() {
        // zxcvbn 本身不在常见口令表里，熵约 28 bit 本来可得 1 分，
        // 但它是 zxcvbnm 的一段，扣两级后归零。
        assertEquals(0, score("zxcvbn"))
    }

    // ── score：强密码必须被判强 ────────────────────────────────────

    @Test
    fun `长句密码满分`() {
        // 28 字符 × log2(59) ≈ 165 bit
        assertEquals(PasswordStrength.MAX_SCORE, score("correct horse battery staple"))
    }

    @Test
    fun `生成的 20 位四类密码满分`() {
        assertEquals(PasswordStrength.MAX_SCORE, score("kR7#mQ2@vX9!bN4\$wZ8&"))
    }

    @Test
    fun `看起来复杂但实为常见口令的模式只有一般分`() {
        // P@ssw0rd1 不在内置表里（表里只有 p@ssw0rd），
        // 熵约 59 bit，落在 40..60 区间 → 2 分。
        assertEquals(2, score("P@ssw0rd1"))
    }

    // ── isWeak / labelOf ──────────────────────────────────────────

    @Test
    fun `零分与一分算弱`() {
        assertTrue(PasswordStrength.isWeak("123456".toCharArray()))
        assertTrue(PasswordStrength.isWeak("abcdefgh".toCharArray()))
        assertFalse(PasswordStrength.isWeak("correct horse battery staple".toCharArray()))
    }

    @Test
    fun `文案覆盖 0 到 4 且越界被夹住`() {
        assertEquals("很弱", PasswordStrength.labelOf(0))
        assertEquals("弱", PasswordStrength.labelOf(1))
        assertEquals("一般", PasswordStrength.labelOf(2))
        assertEquals("强", PasswordStrength.labelOf(3))
        assertEquals("很强", PasswordStrength.labelOf(4))

        assertEquals("很弱", PasswordStrength.labelOf(-5))
        assertEquals("很强", PasswordStrength.labelOf(99))
    }

    @Test
    fun `弱密码阈值与文档一致`() {
        assertEquals(40.0, PasswordStrength.WEAK_ENTROPY_BITS, 0.0)
        assertEquals(4, PasswordStrength.MAX_SCORE)
    }
}
