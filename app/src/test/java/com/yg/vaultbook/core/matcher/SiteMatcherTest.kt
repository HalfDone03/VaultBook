package com.yg.vaultbook.core.matcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排序逻辑的单测。
 *
 * 这里的每一条断言都对应一个真实的填充事故场景：
 * 排错顺序的后果是用户把 A 站点的密码填进了 B 站点的登录框。
 */
class SiteMatcherTest {

    private val now = 1_700_000_000_000L
    private val DAY = 24L * 60 * 60 * 1000

    // ── 基础分 ──────────────────────────────────────────────────

    @Test
    fun `空输入返回空列表`() {
        assertEquals(emptyList<MatchCandidate>(), SiteMatcher.rank(emptyList(), now))
    }

    @Test
    fun `映射命中排在最前`() {
        val result = SiteMatcher.rank(
            listOf(
                RankInput(entryId = "host-only", hostExact = true),
                RankInput(entryId = "mapped", mapped = true)
            ),
            now
        )
        assertEquals("mapped", result.first().entryId)
        assertEquals(100, result.first().score)
    }

    @Test
    fun `host完全一致优先于包名一致`() {
        val result = SiteMatcher.rank(
            listOf(
                RankInput(entryId = "by-pkg", packageExact = true),
                RankInput(entryId = "by-host", hostExact = true)
            ),
            now
        )
        assertEquals(listOf("by-host", "by-pkg"), result.map { it.entryId })
    }

    @Test
    fun `基础分只取最高一项不叠加`() {
        // 同时 hostExact + packageExact + hostSuffix，不能变成 80+70+45
        val result = SiteMatcher.rank(
            listOf(
                RankInput(
                    entryId = "all-bases",
                    hostExact = true,
                    packageExact = true,
                    hostSuffix = true
                )
            ),
            now
        )
        assertEquals(80, result.single().score)
    }

    @Test
    fun `后缀匹配也有分但低于完全一致`() {
        val result = SiteMatcher.rank(
            listOf(
                RankInput(entryId = "suffix", hostSuffix = true),
                RankInput(entryId = "exact", hostExact = true)
            ),
            now
        )
        assertEquals(listOf("exact", "suffix"), result.map { it.entryId })
        assertEquals(45, result.last().score)
    }

    // ── 加分 ────────────────────────────────────────────────────

    @Test
    fun `账号一致加25分`() {
        val result = SiteMatcher.rank(
            listOf(RankInput(entryId = "a", hostExact = true, usernameMatches = true)),
            now
        )
        assertEquals(105, result.single().score)
    }

    @Test
    fun `账号一致能让同级候选分出先后`() {
        // 同一站点两个账号，基础分相同（都只有后缀匹配）：
        // 用户在页面上已经填了 alice，那么 alice 那条应该排在 bob 前面
        val result = SiteMatcher.rank(
            listOf(
                RankInput(entryId = "bob", hostSuffix = true, usernameMatches = false),
                RankInput(entryId = "alice", hostSuffix = true, usernameMatches = true)
            ),
            now
        )
        assertEquals("alice", result.first().entryId)
        assertEquals(70, result.first().score)
    }

    @Test
    fun `账号一致的加分不足以弥补基础分差距`() {
        // 有意的设计：账号一致（+25）压不过"完全匹配 vs 后缀匹配"（80 vs 45）。
        // 页面上填的账号可能是别的账号体系的，网址匹配是更硬的证据。
        val result = SiteMatcher.rank(
            listOf(
                RankInput(entryId = "exact", hostExact = true),
                RankInput(entryId = "suffix-with-user", hostSuffix = true, usernameMatches = true)
            ),
            now
        )
        assertEquals("exact", result.first().entryId)
    }

    @Test
    fun `收藏加6分`() {
        val result = SiteMatcher.rank(
            listOf(RankInput(entryId = "fav", hostExact = true, favorite = true)),
            now
        )
        assertEquals(86, result.single().score)
    }

    @Test
    fun `刚用过加满分`() {
        val result = SiteMatcher.rank(
            listOf(RankInput(entryId = "recent", hostExact = true, lastUsedAt = now)),
            now
        )
        assertEquals(90, result.single().score)
    }

    @Test
    fun `超过30天不再加分`() {
        val result = SiteMatcher.rank(
            listOf(RankInput(entryId = "old", hostExact = true, lastUsedAt = now - 31 * DAY)),
            now
        )
        assertEquals(80, result.single().score)
    }

    @Test
    fun `时钟回拨时按刚用过处理`() {
        // 用户改过系统时间，lastUsedAt 落在"未来"。不能因此给出负分或异常
        val result = SiteMatcher.rank(
            listOf(RankInput(entryId = "future", hostExact = true, lastUsedAt = now + 10 * DAY)),
            now
        )
        assertEquals(90, result.single().score)
    }

    // ── 减分与门槛 ──────────────────────────────────────────────

    @Test
    fun `没有密码扣40分`() {
        val result = SiteMatcher.rank(
            listOf(RankInput(entryId = "no-pwd", hostExact = true, hasPassword = false)),
            now
        )
        assertEquals(40, result.single().score)
    }

    @Test
    fun `没有密码且只有后缀匹配会被门槛丢掉`() {
        // 45 - 40 = 5 < 30：出现在填充列表里却什么都填不上，不如不显示
        val result = SiteMatcher.rank(
            listOf(RankInput(entryId = "weak", hostSuffix = true, hasPassword = false)),
            now
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `低于门槛的候选被丢弃`() {
        val result = SiteMatcher.rank(
            listOf(RankInput(entryId = "nothing")),
            now
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `最多返回5条`() {
        val inputs = (1..10).map { RankInput(entryId = "e$it", hostExact = true) }
        val result = SiteMatcher.rank(inputs, now)
        assertEquals(SiteMatcher.MAX_CANDIDATES, result.size)
    }

    // ── 稳定排序 ────────────────────────────────────────────────

    @Test
    fun `同分时最近用过的排前面`() {
        val result = SiteMatcher.rank(
            listOf(
                RankInput(entryId = "older", hostExact = true, lastUsedAt = now - 20 * DAY),
                RankInput(entryId = "newer", hostExact = true, lastUsedAt = now - 2 * DAY)
            ),
            now
        )
        assertEquals(listOf("newer", "older"), result.map { it.entryId })
    }

    @Test
    fun `完全同分时按id升序保证结果可复现`() {
        val result = SiteMatcher.rank(
            listOf(
                RankInput(entryId = "c", hostExact = true),
                RankInput(entryId = "a", hostExact = true),
                RankInput(entryId = "b", hostExact = true)
            ),
            now
        )
        assertEquals(listOf("a", "b", "c"), result.map { it.entryId })
    }

    @Test
    fun `同一entryId出现多次取最高分`() {
        // Repository 会按 host 和按父域各查一次，同一个条目可能被送进来两次
        val result = SiteMatcher.rank(
            listOf(
                RankInput(entryId = "dup", hostSuffix = true),
                RankInput(entryId = "dup", hostExact = true)
            ),
            now
        )
        assertEquals(1, result.size)
        assertEquals(80, result.single().score)
    }

    @Test
    fun `候选只带entryId不带明文`() {
        val candidate = SiteMatcher.rank(
            listOf(RankInput(entryId = "x", hostExact = true, usernameMatches = true)),
            now
        ).single()
        // 这个对象会被自动填充框架短暂持有，字段里绝不能有账号或密码
        assertEquals("x", candidate.entryId)
        assertTrue(candidate.reasons.isNotEmpty())
    }

    // ── 账号比较 ────────────────────────────────────────────────

    @Test
    fun `账号比较忽略大小写与首尾空白`() {
        assertTrue(SiteMatcher.sameUsername("  Alice@Example.COM ", "alice@example.com"))
    }

    @Test
    fun `账号比较不做模糊匹配`() {
        // abc 与 abcd 不是同一个账号。猜错等于把 A 的密码填进 B 的登录框
        assertFalse(SiteMatcher.sameUsername("abc", "abcd"))
        assertFalse(SiteMatcher.sameUsername("alice", "alice2"))
    }

    @Test
    fun `空账号永远不算匹配`() {
        assertFalse(SiteMatcher.sameUsername(null, null))
        assertFalse(SiteMatcher.sameUsername(null, "alice"))
        assertFalse(SiteMatcher.sameUsername("", ""))
        assertFalse(SiteMatcher.sameUsername("   ", "   "))
    }
}
