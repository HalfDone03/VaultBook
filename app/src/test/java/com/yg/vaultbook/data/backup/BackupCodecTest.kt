package com.yg.vaultbook.data.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份内容的 JSON 编解码。
 *
 * 这里最值得看的是"旧文件能被新版本读"那几条：
 * 备份文件的生命周期比应用版本长得多 —— 用户可能用 1.0 导出的文件
 * 去恢复 1.3 的库。**缺字段一律给默认值，不能让整份文件作废。**
 */
class BackupCodecTest {

    private fun entry(
        id: String = "e1",
        title: String = "淘宝",
        password: CharArray? = "p@ss".toCharArray()
    ) = SnapshotEntry(
        id = id,
        categoryId = "builtin_login",
        title = title,
        username = "alice@example.com",
        password = password,
        url = "https://taobao.com",
        notes = "备注",
        totp = null,
        favorite = true,
        createdAt = 1_700_000_000_000,
        updatedAt = 1_700_000_100_000,
        pwdUpdatedAt = 1_700_000_050_000,
        customFields = listOf(
            SnapshotField(id = "f1", label = "交易密码", value = "123456", type = "secret", sort = 0),
            SnapshotField(id = "f2", label = "柜员号", value = "A-01", type = "text", sort = 1)
        ),
        hostNorm = "taobao.com",
        packageNorm = null,
        lastUsedAt = 1_700_000_200_000,
        useCount = 7,
        deletedAt = null
    )

    private fun category() = SnapshotCategory(
        id = "builtin_login",
        name = "登录",
        icon = null,
        color = null,
        sort = 0,
        builtin = true
    )

    @Test
    fun `空快照也能往返`() {
        val source = VaultSnapshot(createdAt = 123L, categories = emptyList(), entries = emptyList())
        val restored = BackupCodec.decode(BackupCodec.encode(source))
        assertEquals(123L, restored.createdAt)
        assertEquals(0, restored.entryCount)
    }

    @Test
    fun `完整条目往返不丢字段`() {
        val source = VaultSnapshot(
            createdAt = 999L,
            categories = listOf(category()),
            entries = listOf(entry())
        )
        val restored = BackupCodec.decode(BackupCodec.encode(source))
        assertEquals(1, restored.entryCount)

        val e = restored.entries.single()
        assertEquals("e1", e.id)
        assertEquals("builtin_login", e.categoryId)
        assertEquals("淘宝", e.title)
        assertEquals("alice@example.com", e.username)
        assertEquals("p@ss", String(e.password!!))
        assertEquals("https://taobao.com", e.url)
        assertEquals("备注", e.notes)
        assertNull(e.totp)
        assertTrue(e.favorite)
        assertEquals(1_700_000_000_000, e.createdAt)
        assertEquals(1_700_000_100_000, e.updatedAt)
        assertEquals(1_700_000_050_000, e.pwdUpdatedAt)

        // 这四个是"从 url 重算会算错"的字段，必须原样保留
        assertEquals("taobao.com", e.hostNorm)
        assertNull(e.packageNorm)
        assertEquals(1_700_000_200_000, e.lastUsedAt)
        assertEquals(7, e.useCount)
        assertNull(e.deletedAt)
    }

    @Test
    fun `自定义字段的顺序与类型保留`() {
        val source = VaultSnapshot(
            createdAt = 1L,
            categories = emptyList(),
            entries = listOf(entry())
        )
        val restored = BackupCodec.decode(BackupCodec.encode(source))
        val fields = restored.entries.single().customFields
        assertEquals(2, fields.size)
        assertEquals("f1", fields[0].id)
        assertEquals("交易密码", fields[0].label)
        assertEquals("123456", fields[0].value)
        assertEquals("secret", fields[0].type)
        assertEquals(0, fields[0].sort)
        assertEquals("f2", fields[1].id)
        assertEquals(1, fields[1].sort)
    }

    @Test
    fun `没有密码的条目往返后密码仍为 null`() {
        val source = VaultSnapshot(
            createdAt = 1L,
            categories = emptyList(),
            entries = listOf(entry(id = "e2", title = "只有账号", password = null))
        )
        val restored = BackupCodec.decode(BackupCodec.encode(source))
        assertNull(restored.entries.single().password)
        assertFalse(restored.entries.single().customFields.isEmpty())
    }

    @Test
    fun `空密码按没有密码处理`() {
        val source = VaultSnapshot(
            createdAt = 1L,
            categories = emptyList(),
            entries = listOf(entry(password = CharArray(0)))
        )
        val restored = BackupCodec.decode(BackupCodec.encode(source))
        assertNull(restored.entries.single().password)
    }

    @Test
    fun `含引号换行反斜杠的内容不会破坏 JSON`() {
        val tricky = "他说：\"这是\\路径\"\n第二行\t制表符"
        val source = VaultSnapshot(
            createdAt = 1L,
            categories = emptyList(),
            entries = listOf(entry(title = tricky))
        )
        val restored = BackupCodec.decode(BackupCodec.encode(source))
        assertEquals(tricky, restored.entries.single().title)
    }

    @Test
    fun `旧文件缺少新字段时用默认值补上`() {
        // 模拟一个"只用 v 和最少字段"的老文件
        val root = JSONObject().apply {
            put("v", 1)
            put("createdAt", 42L)
            put("entries", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("id", "old-1")
                    put("title", "老条目")
                })
            })
        }
        val restored = BackupCodec.decodeFromJson(root)
        assertEquals(42L, restored.createdAt)
        val e = restored.entries.single()
        assertEquals("old-1", e.id)
        assertEquals("老条目", e.title)
        assertNull(e.username)
        assertNull(e.password)
        assertEquals(0, e.useCount)
        assertFalse(e.favorite)
        assertTrue(e.customFields.isEmpty())
    }

    @Test
    fun `缺少版本号时拒收`() {
        val root = JSONObject().apply {
            put("entries", org.json.JSONArray())
        }
        assertFails<BackupFormatException> { BackupCodec.decodeFromJson(root) }
    }

    @Test
    fun `版本号高于本版本时拒收`() {
        val root = JSONObject().apply {
            put("v", BackupCodec.PAYLOAD_VERSION + 1)
            put("entries", org.json.JSONArray())
        }
        assertFails<BackupFormatException> { BackupCodec.decodeFromJson(root) }
    }

    @Test
    fun `分类往返保留内置标记`() {
        val source = VaultSnapshot(
            createdAt = 1L,
            categories = listOf(category()),
            entries = emptyList()
        )
        val c = BackupCodec.decode(BackupCodec.encode(source)).categories.single()
        assertEquals("builtin_login", c.id)
        assertEquals("登录", c.name)
        assertTrue(c.builtin)
        assertEquals(0, c.sort)
    }

    @Test
    fun `NULL 字段不会被读成字符串 null`() {
        // org.json 的 optString 遇到 JSONObject.NULL 会返回 "null" 这个字符串，
        // 编码器里专门绕开了这一点，这里验证一下没绕漏
        val source = VaultSnapshot(
            createdAt = 1L,
            categories = emptyList(),
            entries = listOf(entry().copy(username = null, url = null, notes = null))
        )
        val restored = BackupCodec.decode(BackupCodec.encode(source))
        assertNull(restored.entries.single().username)
        assertNull(restored.entries.single().url)
        assertNull(restored.entries.single().notes)
    }

    @Test
    fun `中文与 emoji 编码后仍可还原`() {
        val source = VaultSnapshot(
            createdAt = 1L,
            categories = emptyList(),
            entries = listOf(entry(title = "微博·私信🔒"))
        )
        val restored = BackupCodec.decode(BackupCodec.encode(source))
        assertEquals("微博·私信🔒", restored.entries.single().title)
    }

    private inline fun <reified T : Throwable> assertFails(block: () -> Unit): T {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return t
            throw AssertionError("期望 ${T::class.simpleName}，实际 ${t::class.simpleName}: ${t.message}", t)
        }
        throw AssertionError("期望抛 ${T::class.simpleName}，但没有抛")
    }
}
