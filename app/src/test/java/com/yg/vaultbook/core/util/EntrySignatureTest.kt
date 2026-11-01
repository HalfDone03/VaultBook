package com.yg.vaultbook.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EntrySignature] 的行为测试。
 *
 * 去重签名最怕的是「明明是同一条，签名却不一样」—— 那等于用户删了旧记录、
 * 重新粘一遍，以为不会重复，结果库里并排躺两条。所以测试围绕**同一个东西
 * 的不同写法**展开：空白、全角、大小写、有没有填账号。
 *
 * 反过来也要钉住「不一样的不能被认成一样」：不同平台、不同账号必须分开，
 * 否则批量粘贴会把用户的两条记录合成一条（那是静默丢数据，比重复严重得多）。
 */
class EntrySignatureTest {

    @Test
    fun `完全相同就是同一个签名`() {
        assertEquals(
            EntrySignature.of("淘宝", "me@example.com"),
            EntrySignature.of("淘宝", "me@example.com")
        )
    }

    @Test
    fun `首尾空白不影响判定`() {
        assertEquals(
            EntrySignature.of("淘宝", "me@example.com"),
            EntrySignature.of("  淘宝 ", " me@example.com  ")
        )
    }

    @Test
    fun `大小写不影响判定`() {
        assertEquals(
            EntrySignature.of("Apple", "Me@Example.com"),
            EntrySignature.of("apple", "me@example.COM")
        )
    }

    @Test
    fun `全角转半角后同样算同一条`() {
        // 国产输入法经常在中文状态留下全角字母
        assertEquals(
            EntrySignature.of("QQ", "abc123"),
            EntrySignature.of("ＱＱ", "ａｂｃ１２３")
        )
    }

    @Test
    fun `连续空格折叠后算同一条`() {
        assertEquals(
            EntrySignature.of("QQ 邮箱", null),
            EntrySignature.of("QQ   邮箱", null)
        )
    }

    @Test
    fun `账号为空串与没填账号等价`() {
        // 用户选了「只存标题和密码」，账号是 null；后来手填时可能给了空串。
        // 这两种在界面上完全一样，去重时也必须一样。
        assertEquals(
            EntrySignature.of("淘宝", null),
            EntrySignature.of("淘宝", "")
        )
        assertEquals(
            EntrySignature.of("淘宝", null),
            EntrySignature.of("淘宝", "   ")
        )
    }

    @Test
    fun `标题为 null 与空串等价`() {
        assertEquals(EntrySignature.of(null, "abc"), EntrySignature.of("", "abc"))
        assertEquals(EntrySignature.of(null, null), EntrySignature.of("", ""))
    }

    @Test
    fun `不同平台分开`() {
        assertNotEquals(
            EntrySignature.of("淘宝", "me"),
            EntrySignature.of("京东", "me")
        )
    }

    @Test
    fun `同一平台不同账号分开`() {
        assertNotEquals(
            EntrySignature.of("淘宝", "a@example.com"),
            EntrySignature.of("淘宝", "b@example.com")
        )
    }

    @Test
    fun `填了账号与没填账号分开`() {
        assertNotEquals(
            EntrySignature.of("淘宝", "me@example.com"),
            EntrySignature.of("淘宝", null)
        )
    }

    @Test
    fun `标题里的竖线不会撞车`() {
        // 拼签名若用 `|`，这两组会拼出同一个字符串 "a|b|c"。
        assertNotEquals(
            EntrySignature.of("a|b", "c"),
            EntrySignature.of("a", "b|c")
        )
    }

    @Test
    fun `签名里能区分出两边`() {
        val sig = EntrySignature.of("淘宝", "me")
        assertTrue(sig.contains("淘宝"))
        assertTrue(sig.contains("me"))
    }
}
