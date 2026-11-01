package com.yg.vaultbook.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KeyManagerTest {

    private var saltSeed = 0

    /** 每次调用换一个盐，顺便让"改密码是否轮换了盐"可被观测 */
    private fun nextParams(): KdfParams {
        saltSeed += 1
        return KdfParams(
            algo = KdfAlgo.PBKDF2_SHA256,
            iterations = 1_000,   // 单测用低迭代；生产由 KdfParams.newDefault() 给 210k
            salt = ByteArray(KdfParams.SALT_BYTES) { (it + saltSeed).toByte() },
            keyLengthBits = 256
        )
    }

    private fun manager(): KeyManager = KeyManager(paramsFactory = { nextParams() })

    private val password = "master-pass-2026".toCharArray()

    // ── 初始化 ────────────────────────────────────────────────

    @Test
    fun `初始化生成 256 位 DEK 与 dbKey`() {
        val created = manager().createVault(password)
        assertEquals(32, created.keys.dek.size)
        assertEquals(32, created.keys.dbKey.size)
        assertNull("默认不登记设备密钥", created.meta.wrappedDekDevice)
        assertEquals(VaultMetaRecord.SCHEMA_VERSION, created.meta.schemaVersion)
    }

    @Test
    fun `每次初始化得到不同的 DEK`() {
        val km = manager()
        val a = km.createVault(password)
        val b = km.createVault(password)
        assertNotEquals("DEK 不应重复", a.keys.dek.toList(), b.keys.dek.toList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `拒绝过短主密码`() {
        manager().createVault("1234".toCharArray())
    }

    // ── 主密码解锁 ────────────────────────────────────────────

    @Test
    fun `正确主密码可解锁并还原 dbKey`() {
        val km = manager()
        val created = km.createVault(password)
        val unlocked = km.unlockWithPassword(password, created.meta)

        assertArrayEquals(created.keys.dek, unlocked.dek)
        assertArrayEquals(created.keys.dbKey, unlocked.dbKey)
    }

    @Test(expected = WrongPasswordException::class)
    fun `错误主密码被拒绝`() {
        val km = manager()
        val created = km.createVault(password)
        km.unlockWithPassword("wrong-password".toCharArray(), created.meta)
    }

    @Test(expected = WrongPasswordException::class)
    fun `篡改包裹的 DEK 表现为密码错误`() {
        val km = manager()
        val created = km.createVault(password)
        val tampered = created.meta.wrappedDekMaster.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        km.unlockWithPassword(password, created.meta.copyWith(wrappedDekMaster = tampered))
    }

    @Test(expected = TamperedDataException::class)
    fun `篡改 dbKey 包裹块报完整性失败`() {
        val km = manager()
        val created = km.createVault(password)
        val tampered = created.meta.wrappedDbKey.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        km.unlockWithPassword(password, created.meta.copyWith(wrappedDbKey = tampered))
    }

    // ── 改主密码 ──────────────────────────────────────────────

    @Test
    fun `改主密码保持 DEK 与 dbKey 不变`() {
        val km = manager()
        val created = km.createVault(password)
        val oldDek = created.keys.dek.copyOf()
        val oldDbKey = created.keys.dbKey.copyOf()
        val oldKdf = created.meta.kdf.encode()

        val newPassword = "brand-new-master-2026".toCharArray()
        try {
            val changed = km.changeMasterPassword(password, newPassword, created.meta)

            assertArrayEquals("DEK 必须保持不变，否则全库要重新加密", oldDek, changed.keys.dek)
            assertArrayEquals("dbKey 必须保持不变，否则数据库要换口令", oldDbKey, changed.keys.dbKey)
            assertNotEquals("改密码应轮换 KDF 盐", oldKdf, changed.meta.kdf.encode())

            // 旧密码失效
            try {
                km.unlockWithPassword(password, changed.meta)
                fail("旧主密码不应仍能解锁")
            } catch (expected: WrongPasswordException) {
                // 预期路径
            }

            // 新密码可用，且拿到的是同一把 dbKey
            val again = km.unlockWithPassword(newPassword, changed.meta)
            assertArrayEquals(oldDbKey, again.dbKey)
            again.destroy()
        } finally {
            newPassword.fill('\u0000')
            oldDek.fill(0)
            oldDbKey.fill(0)
        }
    }

    @Test(expected = WrongPasswordException::class)
    fun `旧密码错误时改主密码直接失败`() {
        val km = manager()
        val created = km.createVault(password)
        km.changeMasterPassword("not-the-old-one".toCharArray(), "new-master-2026".toCharArray(), created.meta)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `改主密码拒绝过短新密码`() {
        val km = manager()
        val created = km.createVault(password)
        km.changeMasterPassword(password, "123".toCharArray(), created.meta)
    }

    // ── 设备密钥 ──────────────────────────────────────────────

    private fun wrapper() = SecretKeyWrapper(ByteArray(32) { 0x11 })

    @Test
    fun `设备密钥可包裹并解封出同一把 DEK`() {
        val km = manager()
        val created = km.createVault(password)
        val w = wrapper()

        val meta = km.enrollDeviceKey(created.keys, w, created.meta)
        assertNotNull(meta.wrappedDekDevice)

        val unlocked = km.unlockWithDeviceKey(w, meta)
        assertArrayEquals(created.keys.dek, unlocked.dek)
        assertArrayEquals(created.keys.dbKey, unlocked.dbKey)
        unlocked.destroy()
    }

    @Test
    fun `注销设备密钥后无法再用设备密钥解锁`() {
        val km = manager()
        val created = km.createVault(password)
        val w = wrapper()

        val enrolled = km.enrollDeviceKey(created.keys, w, created.meta)
        val revoked = km.revokeDeviceKey(enrolled)
        assertNull(revoked.wrappedDekDevice)

        try {
            km.unlockWithDeviceKey(w, revoked)
            fail("注销后不应还能解锁")
        } catch (expected: IllegalStateException) {
            // 预期路径
        }
    }

    @Test(expected = VaultCorruptedException::class)
    fun `篡改设备包裹块无法解封`() {
        val km = manager()
        val created = km.createVault(password)
        val w = wrapper()

        val meta = km.enrollDeviceKey(created.keys, w, created.meta)
        val tampered = meta.wrappedDekDevice!!.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0xFF).toByte()

        km.unlockWithDeviceKey(w, meta.copyWith(wrappedDekDevice = tampered))
    }

    // ── 密钥销毁 ──────────────────────────────────────────────

    @Test
    fun `destroy 后密钥被清零且重复调用安全`() {
        val km = manager()
        val created = km.createVault(password)
        created.keys.destroy()

        assertTrue(created.keys.isDestroyed)
        assertTrue(created.keys.dek.all { it == 0.toByte() })
        assertTrue(created.keys.dbKey.all { it == 0.toByte() })

        created.keys.destroy()   // 不应抛异常
    }
}
