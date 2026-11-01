package com.yg.vaultbook.domain

import com.yg.vaultbook.core.util.Ids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 领域模型的测试。
 *
 * 重点只有两件事：
 * 1. `clearSecrets()` 真的把密码字符清零了（否则"密码用完就清"只是句口号）
 * 2. `EntrySummary.searchText()` 会拼进账号和网址（否则搜"淘宝"搜不到记了网址的那条）
 */
class EntryDraftTest {

    private fun summary(
        title: String = "淘宝",
        username: String? = "me@example.com",
        url: String? = "https://taobao.com",
        hostNorm: String? = "taobao.com"
    ) = EntrySummary(
        id = "e1",
        categoryId = null,
        title = title,
        username = username,
        url = url,
        hostNorm = hostNorm,
        packageNorm = null,
        favorite = false,
        strengthScore = 3,
        pwdUpdatedAt = null,
        createdAt = 0L,
        updatedAt = 0L,
        lastUsedAt = null,
        useCount = 0,
        hasPassword = true,
        hasTotp = false
    )

    @Test
    fun `clearSecrets 把密码字符全部清零`() {
        val draft = EntryDraft(title = "测试", password = "hunter2".toCharArray())
        assertNotNull(draft.password)
        draft.clearSecrets()
        assertTrue(draft.password!!.all { it == '\u0000' })
    }

    @Test
    fun `密码为 null 时 clearSecrets 不炸`() {
        val draft = EntryDraft(title = "无密码条目")
        draft.clearSecrets()
        assertEquals(null, draft.password)
    }

    @Test
    fun `没有密码的新建草稿 password 为 null 表示不设置`() {
        val draft = EntryDraft(title = "笔记")
        assertEquals(null, draft.password)
        assertEquals("笔记", draft.title)
        assertFalse(draft.favorite)
        assertTrue(draft.customFields.isEmpty())
    }

    @Test
    fun `searchText 含标题账号网址与主机名`() {
        val text = summary().searchText()
        assertTrue(text.contains("淘宝"))
        assertTrue(text.contains("me@example.com"))
        assertTrue(text.contains("https://taobao.com"))
        assertTrue(text.contains("taobao.com"))
    }

    @Test
    fun `searchText 用不可见分隔符避免跨字段误匹配`() {
        // 用 \u0001 拼接，所以 "abc"+"def" 不会拼成 "abcdef" 被误命中
        val a = summary(title = "abc", username = "def", url = null, hostNorm = null)
        assertFalse(a.searchText().contains("abcdef"))
        assertTrue(a.searchText().contains("abc\u0001def"))
    }

    @Test
    fun `searchText 跳过为空的字段`() {
        val text = summary(title = "只有标题", username = null, url = null, hostNorm = null).searchText()
        assertEquals("只有标题", text)
    }

    // ── Ids ───────────────────────────────────────────────────────

    @Test
    fun `newId 生成唯一且非空的 id`() {
        val ids = (1..200).map { Ids.newId() }.toSet()
        assertEquals(200, ids.size)
        ids.forEach { assertEquals(36, it.length) }
    }
}
