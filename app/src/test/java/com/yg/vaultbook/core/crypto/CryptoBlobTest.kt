package com.yg.vaultbook.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CryptoBlobTest {

    private val key = ByteArray(32) { (it * 5 + 3).toByte() }
    private val aad = "vb:test:aad".toByteArray(Charsets.UTF_8)

    // ── 密文包结构 ────────────────────────────────────────────

    @Test
    fun `pack 与 unpack 保留头部字段`() {
        val plaintext = "hello".toByteArray(Charsets.UTF_8)
        val blob = Aead.seal(key, aad, plaintext)
        val unpacked = CryptoBlob.unpack(blob)

        assertEquals(CryptoBlob.VERSION.toInt(), unpacked.version.toInt())
        assertEquals(CryptoBlob.FLAG_AAD_BOUND.toInt(), unpacked.flags.toInt())
        assertEquals(CryptoBlob.IV_BYTES, unpacked.iv.size)
        assertEquals(plaintext.size + CryptoBlob.TAG_BYTES, unpacked.payload.size)
        assertEquals(CryptoBlob.MIN_BYTES + plaintext.size, blob.size)
    }

    @Test(expected = CryptoFormatException::class)
    fun `过短密文被拒绝`() {
        CryptoBlob.unpack(ByteArray(10))
    }

    @Test(expected = CryptoFormatException::class)
    fun `未知版本被拒绝`() {
        val blob = ByteArray(CryptoBlob.MIN_BYTES)
        blob[0] = 0x09
        CryptoBlob.unpack(blob)
    }

    // ── 加解密往返 ────────────────────────────────────────────

    @Test
    fun `GCM 往返`() {
        val plaintext = "account-password-中文-🔐".toByteArray(Charsets.UTF_8)
        val blob = Aead.seal(key, aad, plaintext)
        val back = Aead.open(key, aad, blob)
        assertArrayEquals(plaintext, back)
    }

    @Test
    fun `空明文也能往返`() {
        val blob = Aead.seal(key, aad, ByteArray(0))
        assertEquals(0, Aead.open(key, aad, blob).size)
    }

    // ── 随机 IV ───────────────────────────────────────────────

    @Test
    fun `同一明文两次加密得到不同密文`() {
        val plaintext = "same".toByteArray(Charsets.UTF_8)
        val a = Aead.seal(key, aad, plaintext)
        val b = Aead.seal(key, aad, plaintext)
        assertFalse("IV 复用了", a.contentEquals(b))

        val ivA = CryptoBlob.unpack(a).iv
        val ivB = CryptoBlob.unpack(b).iv
        assertFalse("IV 相同", ivA.contentEquals(ivB))
    }

    // ── 篡改检测 ──────────────────────────────────────────────

    @Test(expected = TamperedDataException::class)
    fun `篡改密文体被检测`() {
        val blob = Aead.seal(key, aad, "secret".toByteArray(Charsets.UTF_8))
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()
        Aead.open(key, aad, blob)
    }

    @Test(expected = TamperedDataException::class)
    fun `篡改 IV 被检测`() {
        val blob = Aead.seal(key, aad, "secret".toByteArray(Charsets.UTF_8))
        blob[2] = (blob[2].toInt() xor 0x01).toByte()
        Aead.open(key, aad, blob)
    }

    @Test(expected = TamperedDataException::class)
    fun `篡改 flags 被检测（flags 已纳入 AAD）`() {
        val blob = Aead.seal(key, aad, "secret".toByteArray(Charsets.UTF_8))
        blob[1] = 0x00
        Aead.open(key, aad, blob)
    }

    @Test(expected = TamperedDataException::class)
    fun `AAD 不一致被检测`() {
        val blob = Aead.seal(key, aad, "secret".toByteArray(Charsets.UTF_8))
        Aead.open(key, "vb:other:aad".toByteArray(Charsets.UTF_8), blob)
    }

    @Test(expected = TamperedDataException::class)
    fun `密钥不一致被检测`() {
        val blob = Aead.seal(key, aad, "secret".toByteArray(Charsets.UTF_8))
        val otherKey = ByteArray(32) { (it * 11 + 1).toByte() }
        Aead.open(otherKey, aad, blob)
    }

    @Test
    fun `密文长度随明文增长`() {
        val short = Aead.seal(key, aad, ByteArray(1))
        val long = Aead.seal(key, aad, ByteArray(100))
        assertEquals(CryptoBlob.HEADER_BYTES + CryptoBlob.TAG_BYTES + 1, short.size)
        assertEquals(CryptoBlob.HEADER_BYTES + CryptoBlob.TAG_BYTES + 100, long.size)
        assertNotEquals(short.size, long.size)
    }

    // ── 参数校验 ──────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `非法密钥长度被拒绝包装`() {
        Aead.seal(ByteArray(20), aad, ByteArray(1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `pack 拒绝错误长度的 IV`() {
        CryptoBlob.pack(ByteArray(8), ByteArray(CryptoBlob.TAG_BYTES))
    }
}
