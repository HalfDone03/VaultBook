package com.yg.vaultbook.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FieldCipherTest {

    private val dek = ByteArray(32) { (it * 7 + 3).toByte() }
    private val cipher = FieldCipher(dek)

    private val entryId = "e_01HXYZ0001"
    private val otherEntry = "e_01HXYZ0002"

    // ── 往返 ──────────────────────────────────────────────────

    @Test
    fun `密码字段往返（中文与 emoji）`() {
        val secret = "我的密码🔑Aa1!".toCharArray()
        val blob = cipher.encrypt(entryId, "password", secret)
        val back = cipher.decrypt(entryId, "password", blob)!!
        assertArrayEquals(secret, back)
    }

    @Test
    fun `长密码往返`() {
        val secret = ("x".repeat(500) + "尾").toCharArray()
        val blob = cipher.encrypt(entryId, "password", secret)
        assertArrayEquals(secret, cipher.decrypt(entryId, "password", blob)!!)
    }

    @Test
    fun `空值与 null 返回 null`() {
        assertNull(cipher.encrypt(entryId, "password", null))
        assertNull(cipher.encrypt(entryId, "password", charArrayOf()))
        assertNull(cipher.decrypt(entryId, "password", null))
        assertNull(cipher.decrypt(entryId, "password", ByteArray(0)))
    }

    // ── 非确定性 ──────────────────────────────────────────────

    @Test
    fun `同一字段两次加密结果不同`() {
        val secret = "same-password".toCharArray()
        val a = cipher.encrypt(entryId, "password", secret)
        val b = cipher.encrypt(entryId, "password", secret)
        assertFalse(a!!.contentEquals(b!!))
    }

    // ── AAD 绑定：阻止密文搬运 ────────────────────────────────

    @Test(expected = TamperedDataException::class)
    fun `密文不能搬到同一条目的另一个字段`() {
        val blob = cipher.encrypt(entryId, "password", "secret".toCharArray())
        cipher.decrypt(entryId, "notes", blob)
    }

    @Test(expected = TamperedDataException::class)
    fun `密文不能搬到另一条记录的同名字段`() {
        val blob = cipher.encrypt(entryId, "password", "secret".toCharArray())
        cipher.decrypt(otherEntry, "password", blob)
    }

    // ── 篡改 ──────────────────────────────────────────────────

    @Test(expected = TamperedDataException::class)
    fun `篡改密文被检测`() {
        val blob = cipher.encrypt(entryId, "password", "secret".toCharArray())!!
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()
        cipher.decrypt(entryId, "password", blob)
    }

    @Test(expected = CryptoFormatException::class)
    fun `过短密文被拒绝`() {
        cipher.decrypt(entryId, "password", ByteArray(5))
    }

    // ── 字符串便捷入口（非敏感字段） ──────────────────────────

    @Test
    fun `字符串字段往返`() {
        val url = "https://example.com/login?next=/home"
        val blob = cipher.encryptString(entryId, "url", url)
        assertEquals(url, cipher.decryptString(entryId, "url", blob))
    }

    @Test
    fun `字符串入口对 null 与空串的处理`() {
        assertNull(cipher.encryptString(entryId, "title", null))
        assertNull(cipher.encryptString(entryId, "title", ""))
        assertNull(cipher.decryptString(entryId, "title", null))
    }

    // ── 施工约束 ──────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `拒绝非 256 位 DEK`() {
        FieldCipher(ByteArray(16))
    }
}
