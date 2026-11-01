package com.yg.vaultbook.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class KdfTest {

    private val salt = ByteArray(KdfParams.SALT_BYTES) { (it * 3 + 1).toByte() }
    private val password = "correct horse battery staple".toCharArray()

    private fun params(
        iterations: Int = 10_000,
        salt: ByteArray = this.salt,
        bits: Int = 256
    ) = KdfParams(KdfAlgo.PBKDF2_SHA256, iterations, salt, bits)

    // ── 派生结果 ──────────────────────────────────────────────

    @Test
    fun `默认派生 256 位密钥`() {
        assertEquals(32, Kdf.derive(password, params()).size)
    }

    @Test
    fun `相同密码与盐派生结果一致`() {
        val a = Kdf.derive(password, params(salt = salt.copyOf()))
        val b = Kdf.derive(password, params(salt = salt.copyOf()))
        assertArrayEquals(a, b)
    }

    @Test
    fun `不同盐派生结果不同`() {
        val other = ByteArray(KdfParams.SALT_BYTES) { (it * 3 + 2).toByte() }
        val a = Kdf.derive(password, params(salt = salt))
        val b = Kdf.derive(password, params(salt = other))
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `不同迭代次数派生结果不同`() {
        val a = Kdf.derive(password, params(iterations = 10_000))
        val b = Kdf.derive(password, params(iterations = 10_001))
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `不同密码派生结果不同`() {
        val a = Kdf.derive(password, params())
        val b = Kdf.derive("wrong password".toCharArray(), params())
        assertFalse(a.contentEquals(b))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `空密码被拒绝`() {
        Kdf.derive(charArrayOf(), params())
    }

    // ── 参数序列化 ────────────────────────────────────────────

    @Test
    fun `参数序列化可完整往返`() {
        val original = KdfParams(
            algo = KdfAlgo.PBKDF2_SHA256,
            iterations = KdfParams.DEFAULT_ITERATIONS,
            salt = salt,
            keyLengthBits = 256
        )
        val decoded = KdfParams.decode(original.encode())

        assertEquals(original.algo, decoded.algo)
        assertEquals(original.iterations, decoded.iterations)
        assertEquals(original.keyLengthBits, decoded.keyLengthBits)
        assertArrayEquals(original.salt, decoded.salt)
    }

    @Test
    fun `序列化字符串是人类可读的`() {
        val p = KdfParams(KdfAlgo.PBKDF2_SHA256, 210_000, salt, 256)
        val encoded = p.encode()
        assertTrue(encoded.contains("algo=pbkdf2-sha256"))
        assertTrue(encoded.contains("iter=210000"))
        assertTrue(encoded.contains("len=256"))
    }

    // ── 降级防御 ──────────────────────────────────────────────

    @Test(expected = CryptoFormatException::class)
    fun `解析时拒绝过低迭代次数（防 KDF 降级攻击）`() {
        val saltB64 = Base64.getEncoder().encodeToString(salt)
        KdfParams.decode("algo=pbkdf2-sha256;iter=1;salt=$saltB64;len=256")
    }

    @Test(expected = CryptoFormatException::class)
    fun `解析时拒绝未知算法`() {
        val saltB64 = Base64.getEncoder().encodeToString(salt)
        KdfParams.decode("algo=argon2id;iter=210000;salt=$saltB64;len=256")
    }

    @Test(expected = CryptoFormatException::class)
    fun `解析时拒绝缺失 salt`() {
        KdfParams.decode("algo=pbkdf2-sha256;iter=210000;len=256")
    }

    @Test(expected = CryptoFormatException::class)
    fun `解析时拒绝非法 Base64 salt`() {
        KdfParams.decode("algo=pbkdf2-sha256;iter=210000;salt=***;len=256")
    }

    @Test(expected = CryptoFormatException::class)
    fun `解析时拒绝非数字迭代次数`() {
        val saltB64 = Base64.getEncoder().encodeToString(salt)
        KdfParams.decode("algo=pbkdf2-sha256;iter=many;salt=$saltB64;len=256")
    }

    // ── 构造器校验 ────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `构造器拒绝零迭代`() {
        KdfParams(KdfAlgo.PBKDF2_SHA256, 0, salt)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `构造器拒绝空盐`() {
        KdfParams(KdfAlgo.PBKDF2_SHA256, 10_000, ByteArray(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `构造器拒绝非法密钥长度`() {
        KdfParams(KdfAlgo.PBKDF2_SHA256, 10_000, salt, 512)
    }
}
