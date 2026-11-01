package com.yg.vaultbook.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份文件容器格式。
 *
 * 这些测试的重点不是"能装能拆"，而是**结构被改动时会拒收**：
 * 备份文件会离开这台手机（网盘、U 盘、聊天记录），
 * 它是唯一一个会被拿到应用之外去摆弄的产物。
 */
class BackupFormatTest {

    private val kdfParams = "algo=pbkdf2-sha256;iter=210000;salt=AAAA;len=256"

    private fun sample(): ByteArray {
        val iv = ByteArray(BackupFormat.IV_BYTES) { (it + 1).toByte() }
        val ciphertext = ByteArray(32) { (it * 3).toByte() }
        return BackupFormat.pack(kdfParams, iv, ciphertext)
    }

    @Test
    fun `pack 与 unpack 往返一致`() {
        val bytes = sample()
        val c = BackupFormat.unpack(bytes)
        assertEquals(kdfParams, c.kdfParams)
        assertEquals(BackupFormat.IV_BYTES, c.iv.size)
        assertEquals(32, c.ciphertext.size)
        assertEquals(BackupFormat.FIXED_HEADER_BYTES + kdfParams.length, BackupFormat.aad(bytes).size)
    }

    @Test
    fun `文件头以 VBCK 开头`() {
        val bytes = sample()
        assertEquals('V'.code.toByte(), bytes[0])
        assertEquals('B'.code.toByte(), bytes[1])
        assertEquals('C'.code.toByte(), bytes[2])
        assertEquals('K'.code.toByte(), bytes[3])
        assertEquals(BackupFormat.VERSION.toByte(), bytes[4])
    }

    @Test
    fun `魔数不对时拒收`() {
        val bytes = sample()
        bytes[0] = 'X'.code.toByte()
        assertFails<BackupFormatException> { BackupFormat.unpack(bytes) }
    }

    @Test
    fun `版本过高时拒收并提示升级`() {
        val bytes = sample()
        bytes[4] = 99.toByte()
        val e = assertFails<BackupFormatException> { BackupFormat.unpack(bytes) }
        assertTrue("消息应提示升级，实际：${e.message}", e.message!!.contains("升级"))
    }

    @Test
    fun `过短的输入被拒收而不是抛越界`() {
        assertFails<BackupFormatException> { BackupFormat.unpack(ByteArray(0)) }
        assertFails<BackupFormatException> { BackupFormat.unpack(ByteArray(BackupFormat.MIN_BYTES - 1)) }
    }

    @Test
    fun `kdfLen 被改大导致长度不符时拒收`() {
        val bytes = sample()
        // 把 kdfLen 从 43 改成 900：剩余字节数不够了
        bytes[5] = 0x03
        bytes[6] = 0x84.toByte()
        assertFails<BackupFormatException> { BackupFormat.unpack(bytes) }
    }

    @Test
    fun `kdfLen 超过上限时拒收`() {
        val bytes = sample()
        bytes[5] = 0x7F
        bytes[6] = 0xFF.toByte()
        assertFails<BackupFormatException> { BackupFormat.unpack(bytes) }
    }

    @Test
    fun `截断到装不下 IV 与 TAG 时拒收`() {
        // 注意：`unpack` 只做结构下限校验。截掉一两个字节它拦不住 ——
        // 那是 GCM 的 TAG 负责的事。这里验证的是"连结构都不成立"的情况。
        val bytes = sample()
        val cut = bytes.copyOfRange(
            0,
            BackupFormat.FIXED_HEADER_BYTES + kdfParams.length + BackupFormat.IV_BYTES
        )
        assertFails<BackupFormatException> { BackupFormat.unpack(cut) }
    }

    @Test
    fun `looksLikeBackup 只认魔数`() {
        assertTrue(BackupFormat.looksLikeBackup(sample()))
        assertFalse(BackupFormat.looksLikeBackup(byteArrayOf(1, 2, 3, 4, 5, 6, 7)))
        assertFalse(BackupFormat.looksLikeBackup(ByteArray(0)))
    }

    @Test
    fun `aad 覆盖整个头 改 kdf 参数会让 aad 不同`() {
        val a = sample()
        val b = BackupFormat.pack(
            kdfParams.replace("iter=210000", "iter=1"),
            ByteArray(BackupFormat.IV_BYTES),
            ByteArray(32)
        )
        // 长度可能不同，但相同的部分必须不同 —— 否则降级攻击无法被发现
        assertFalse(
            "两段 AAD 不应相同",
            BackupFormat.aad(a).contentEquals(BackupFormat.aad(b))
        )
    }

    @Test
    fun `readUInt16 按大端解析`() {
        val bytes = byteArrayOf(0x01, 0x02)
        assertEquals(0x0102, BackupFormat.readUInt16(bytes, 0))
        val two = byteArrayOf(0x00, 0xFF.toByte())
        assertEquals(255, BackupFormat.readUInt16(two, 0))
    }

    @Test
    fun `hex 只显示前 limit 字节`() {
        val bytes = ByteArray(20) { it.toByte() }
        val text = BackupFormat.hex(bytes, limit = 4)
        assertTrue(text.startsWith("00 01 02 03"))
        assertFalse(text.contains("04"))
    }

    @Test
    fun `describeHeader 能读出内容`() {
        val text = BackupFormat.describeHeader(sample())
        assertTrue(text.isNotEmpty())
        assertTrue("应包含 KDF 算法名，实际：$text", text.contains("pbkdf2"))
    }

    @Test
    fun `容器里的 iv 与写进去的一致`() {
        val iv = ByteArray(BackupFormat.IV_BYTES) { (it + 7).toByte() }
        val bytes = BackupFormat.pack(kdfParams, iv, ByteArray(16))
        val c = BackupFormat.unpack(bytes)
        assertArrayEquals(iv, c.iv)
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
