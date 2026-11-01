package com.yg.vaultbook.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Utf8Test {

    private fun roundTrip(text: String): String {
        val chars = text.toCharArray()
        val bytes = Utf8.encode(chars)
        val back = Utf8.decode(bytes)
        return String(back)
    }

    @Test
    fun `ASCII 往返`() {
        assertEquals("VaultBook-123!@#", roundTrip("VaultBook-123!@#"))
    }

    @Test
    fun `中文往返`() {
        assertEquals("账号本·我的密码管理器", roundTrip("账号本·我的密码管理器"))
    }

    @Test
    fun `emoji 代理对往返`() {
        assertEquals("🔐🔑🛡️", roundTrip("🔐🔑🛡️"))
    }

    @Test
    fun `混合文本往返`() {
        val s = "Pa55·中文·🔐·ñ·Ω"
        assertEquals(s, roundTrip(s))
    }

    @Test
    fun `空输入返回空数组`() {
        assertEquals(0, Utf8.encode(charArrayOf()).size)
        assertEquals(0, Utf8.decode(ByteArray(0)).size)
    }

    @Test
    fun `编码长度符合 UTF-8 规范`() {
        assertEquals(1, Utf8.encode(charArrayOf('A')).size)          // U+0041
        assertEquals(2, Utf8.encode(charArrayOf('\u00A2')).size)     // U+00A2
        assertEquals(3, Utf8.encode(charArrayOf('中')).size)          // U+4E2D
        assertEquals(4, Utf8.encode(charArrayOf('\uD83D', '\uDD10')).size) // U+1F510
    }

    @Test
    fun `孤立高代理被替换为 U+FFFD`() {
        val chars = charArrayOf('\uD83D', 'a')
        val back = Utf8.decode(Utf8.encode(chars))
        assertTrue(back.contains('\uFFFD'))
        assertTrue(back.contains('a'))
    }

    @Test
    fun `孤立低代理被替换为 U+FFFD`() {
        val chars = charArrayOf('\uDD10', 'b')
        val back = Utf8.decode(Utf8.encode(chars))
        assertTrue(back.contains('\uFFFD'))
        assertTrue(back.contains('b'))
    }

    @Test
    fun `非法字节序列被替换而不是抛异常`() {
        // 0xFF 不是任何合法 UTF-8 起始字节
        val back = Utf8.decode(byteArrayOf(0xFF.toByte(), 0x41.toByte()))
        assertEquals(2, back.size)
        assertEquals('\uFFFD', back[0])
        assertEquals('A', back[1])
    }

    @Test
    fun `解密容错不抛异常（截断的多字节序列）`() {
        // 只给 3 字节序列的前 2 字节
        val back = Utf8.decode(byteArrayOf(0xE4.toByte(), 0xB8.toByte()))
        assertEquals(2, back.size)
    }
}
