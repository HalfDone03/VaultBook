package com.yg.vaultbook.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultSessionTest {

    private fun keys() = VaultKeys(ByteArray(32) { 1 }, ByteArray(32) { 2 })

    // ── 基本状态 ──────────────────────────────────────────────

    @Test
    fun `初始为锁定状态`() {
        val s = VaultSession()
        assertEquals(VaultState.LOCKED, s.state.value)
        assertFalse(s.isUnlocked)
    }

    @Test
    fun `解锁后状态与加密器可用`() {
        val s = VaultSession()
        s.unlock(keys())
        assertTrue(s.isUnlocked)
        assertEquals(VaultState.UNLOCKED, s.state.value)
        s.requireCipher()   // 不应抛
    }

    @Test(expected = IllegalStateException::class)
    fun `锁定时访问加密器抛异常`() {
        VaultSession().requireCipher()
    }

    // ── 锁定 ──────────────────────────────────────────────────

    @Test
    fun `锁定会销毁密钥`() {
        val s = VaultSession()
        val k = keys()
        s.unlock(k)
        s.lock()

        assertTrue("密钥应被清零", k.isDestroyed)
        assertFalse(s.isUnlocked)
        assertEquals(VaultState.LOCKED, s.state.value)
    }

    @Test(expected = IllegalStateException::class)
    fun `锁定后加密器不可用`() {
        val s = VaultSession()
        s.unlock(keys())
        s.lock()
        s.requireCipher()
    }

    @Test
    fun `重复解锁会销毁旧密钥`() {
        val s = VaultSession()
        val first = keys()
        s.unlock(first)
        s.unlock(keys())

        assertTrue("旧密钥应被销毁", first.isDestroyed)
        assertTrue(s.isUnlocked)
    }

    // ── 自动锁定 ──────────────────────────────────────────────

    @Test
    fun `后台超过阈值自动锁定`() {
        var now = 0L
        val s = VaultSession(autoLockTimeoutMs = { 30_000L }, clock = { now })
        s.unlock(keys())

        s.onEnterBackground()
        now = 30_001L

        assertTrue("应判定为超时并锁定", s.onEnterForeground())
        assertEquals(VaultState.LOCKED, s.state.value)
    }

    @Test
    fun `恰好等于阈值即锁定`() {
        var now = 0L
        val s = VaultSession(autoLockTimeoutMs = { 30_000L }, clock = { now })
        s.unlock(keys())

        s.onEnterBackground()
        now = 30_000L

        assertTrue(s.onEnterForeground())
    }

    @Test
    fun `后台未超过阈值保持解锁`() {
        var now = 0L
        val s = VaultSession(autoLockTimeoutMs = { 30_000L }, clock = { now })
        s.unlock(keys())

        s.onEnterBackground()
        now = 10_000L

        assertFalse(s.onEnterForeground())
        assertTrue(s.isUnlocked)
    }

    @Test
    fun `从未进入后台时回到前台不锁定`() {
        var now = 0L
        val s = VaultSession(autoLockTimeoutMs = { 30_000L }, clock = { now })
        s.unlock(keys())

        now = 1_000_000L

        assertFalse(s.onEnterForeground())
        assertTrue(s.isUnlocked)
    }

    @Test
    fun `回到前台后计时归零并重新计时`() {
        var now = 0L
        val s = VaultSession(autoLockTimeoutMs = { 30_000L }, clock = { now })
        s.unlock(keys())

        s.onEnterBackground()
        now = 5_000L
        assertFalse(s.onEnterForeground())    // 未超时，计时归零

        s.onEnterBackground()
        now = 20_000L
        assertFalse(s.onEnterForeground())    // 距第二次后台仅 15s
        assertTrue(s.isUnlocked)
    }

    @Test
    fun `锁定状态下回到前台不报错`() {
        val s = VaultSession()
        assertFalse(s.onEnterForeground())
        assertEquals(VaultState.LOCKED, s.state.value)
    }

    @Test
    fun `锁定后后台计时被重置`() {
        var now = 0L
        val s = VaultSession(autoLockTimeoutMs = { 30_000L }, clock = { now })
        s.unlock(keys())

        s.onEnterBackground()
        s.lock()
        s.unlock(keys())      // 重新解锁，计时应已重置

        now = 100_000L
        assertFalse("重新解锁后不应因旧计时被锁定", s.onEnterForeground())
        assertTrue(s.isUnlocked)
    }
}
