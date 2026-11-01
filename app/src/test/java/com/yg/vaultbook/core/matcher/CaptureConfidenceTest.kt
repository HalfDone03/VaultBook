package com.yg.vaultbook.core.matcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 捕获置信度打分。
 *
 * 这套阈值的用途：决定"要不要因为一次登录而弹出保存提示"。
 * 偏保守是刻意的 —— 漏存一条用户还能手动补，错存一条会污染
 * "这个站点用哪个账号"的学习结果，而错误数据比没有数据更难纠正。
 */
class CaptureConfidenceTest {

    // ── 打分 ────────────────────────────────────────────────────

    @Test
    fun `全部满足得满分`() {
        val score = CaptureConfidence.score(
            hasPassword = true,
            hasUsername = true,
            hasSiteIdentity = true,
            submitted = true,
            hasTitle = true
        )
        assertEquals(1.0f, score, 0.0001f)
    }

    @Test
    fun `什么都没有得零分`() {
        val score = CaptureConfidence.score(
            hasPassword = false,
            hasUsername = false,
            hasSiteIdentity = false,
            submitted = false,
            hasTitle = false
        )
        assertEquals(0.0f, score, 0.0001f)
    }

    @Test
    fun `只有密码框是035分`() {
        val score = CaptureConfidence.score(
            hasPassword = true,
            hasUsername = false,
            hasSiteIdentity = false,
            submitted = false,
            hasTitle = false
        )
        assertEquals(0.35f, score, 0.0001f)
    }

    @Test
    fun `密码加账号正好是待确认门槛`() {
        val score = CaptureConfidence.score(
            hasPassword = true,
            hasUsername = true,
            hasSiteIdentity = false,
            submitted = false,
            hasTitle = false
        )
        assertEquals(0.60f, score, 0.0001f)
        assertEquals(CaptureVerdict.PENDING, CaptureConfidence.verdict(score))
    }

    @Test
    fun `密码加站点身份差一点到门槛`() {
        // 0.35 + 0.20 = 0.55 < 0.60：没有账号框的页面（银行分步登录常见）会被丢弃。
        // 这是有意的取舍 —— 只有密码、站点还对不上时，错存的风险高于漏存
        val score = CaptureConfidence.score(
            hasPassword = true,
            hasUsername = false,
            hasSiteIdentity = true,
            submitted = false,
            hasTitle = false
        )
        assertEquals(0.55f, score, 0.0001f)
        assertEquals(CaptureVerdict.DISCARD, CaptureConfidence.verdict(score))
    }

    @Test
    fun `密码账号站点三者齐全是080分`() {
        val score = CaptureConfidence.score(
            hasPassword = true,
            hasUsername = true,
            hasSiteIdentity = true,
            submitted = false,
            hasTitle = false
        )
        assertEquals(0.80f, score, 0.0001f)
        assertEquals(CaptureVerdict.PENDING, CaptureConfidence.verdict(score))
    }

    @Test
    fun `加上提交证据进入高可信`() {
        val score = CaptureConfidence.score(
            hasPassword = true,
            hasUsername = true,
            hasSiteIdentity = true,
            submitted = true,
            hasTitle = false
        )
        assertEquals(0.90f, score, 0.0001f)
        assertEquals(CaptureVerdict.STRONG, CaptureConfidence.verdict(score))
    }

    @Test
    fun `标题单独也能把分数推进高可信`() {
        val score = CaptureConfidence.score(
            hasPassword = true,
            hasUsername = true,
            hasSiteIdentity = true,
            submitted = false,
            hasTitle = true
        )
        assertEquals(0.90f, score, 0.0001f)
        assertEquals(CaptureVerdict.STRONG, CaptureConfidence.verdict(score))
    }

    @Test
    fun `分数收敛到两位小数`() {
        // 浮点累加会产生 0.60000004 这类值，不收敛的话阈值判断会在边界随机翻转
        repeat(50) {
            val score = CaptureConfidence.score(
                hasPassword = true,
                hasUsername = true,
                hasSiteIdentity = false,
                submitted = false,
                hasTitle = false
            )
            assertEquals(0.60f, score, 0.0001f)
        }
    }

    @Test
    fun `分数被夹在0到1之间`() {
        val score = CaptureConfidence.score(
            hasPassword = true,
            hasUsername = true,
            hasSiteIdentity = true,
            submitted = true,
            hasTitle = true
        )
        assertTrue(score <= 1.0f)
        assertTrue(score >= 0.0f)
    }

    // ── 判定 ────────────────────────────────────────────────────

    @Test
    fun `判定边界`() {
        assertEquals(CaptureVerdict.STRONG, CaptureConfidence.verdict(0.85f))
        assertEquals(CaptureVerdict.PENDING, CaptureConfidence.verdict(0.8499f))
        assertEquals(CaptureVerdict.PENDING, CaptureConfidence.verdict(0.60f))
        assertEquals(CaptureVerdict.DISCARD, CaptureConfidence.verdict(0.5999f))
        assertEquals(CaptureVerdict.DISCARD, CaptureConfidence.verdict(0f))
    }

    @Test
    fun `阈值常量符合文档`() {
        assertEquals(0.85f, CaptureConfidence.STRONG_THRESHOLD, 0.0001f)
        assertEquals(0.60f, CaptureConfidence.PENDING_THRESHOLD, 0.0001f)
    }
}
