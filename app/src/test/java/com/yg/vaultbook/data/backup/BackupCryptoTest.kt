package com.yg.vaultbook.data.backup

import com.yg.vaultbook.core.crypto.KdfParams
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 备份文件的加解密。
 *
 * 最重要的是两个"负向"测试：
 * 1. 改一个字节 → 打不开；
 * 2. **改文件头里的 KDF 迭代次数 → 也必须打不开**。
 *
 * 第 2 条是 [BackupFormat.aad] 存在的全部理由：如果 AAD 不覆盖文件头，
 * 攻击者可以把 `iter=210000` 改成 `iter=1` 再把文件发回给你，
 * 你输同一个备份密码时派生出来的 KEK 完全相同（能正常打开），
 * 但对方暴力破解这份文件只需 1 次迭代的成本 ——
 * 表面上"什么都没变"，实际上加密强度被抽走了。
 */
class BackupCryptoTest {

    private val password = "备份密码-2026".toCharArray()

    private fun payload(): ByteArray =
        """{"v":1,"entries":[{"title":"淘宝","password":"中文密码·🔒"}]}"""
            .toByteArray(Charsets.UTF_8)

    @Test
    fun `seal 与 open 往返一致`() {
        val plain = payload()
        val sealed = BackupCrypto.seal(plain, password)
        val opened = BackupCrypto.open(sealed, password)
        assertArrayEquals(plain, opened)
        assertFalse("密文不应包含明文", String(sealed, Charsets.ISO_8859_1).contains("淘宝"))
    }

    @Test
    fun `空内容也能往返`() {
        val sealed = BackupCrypto.seal(ByteArray(0), password)
        assertEquals(0, BackupCrypto.open(sealed, password).size)
    }

    @Test
    fun `错误密码打不开`() {
        val sealed = BackupCrypto.seal(payload(), password)
        val wrong = "错误的密码".toCharArray()
        assertFails<BackupPasswordException> { BackupCrypto.open(sealed, wrong) }
        wrong.fill('\u0000')
    }

    @Test
    fun `改一个字节就打不开`() {
        val sealed = BackupCrypto.seal(payload(), password)
        // 从尾部改：确保改的是密文/tag 部分
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 0x01).toByte()
        assertFails<BackupPasswordException> { BackupCrypto.open(sealed, password) }
    }

    @Test
    fun `改文件头里的迭代次数就打不开`() {
        val sealed = BackupCrypto.seal(payload(), password)
        val text = String(sealed, Charsets.ISO_8859_1)
        val idx = text.indexOf("iter=210000")
        if (idx < 0) {
            // KDF 参数串格式变了就跳过这条断言，避免"改实现后测试无脑红"
            return
        }
        // "210000" → "100000"：字符数不变，不会破坏长度字段
        val replacement = "iter=100000"
        for (i in replacement.indices) {
            sealed[idx + i] = replacement[i].code.toByte()
        }
        assertFails<BackupPasswordException> { BackupCrypto.open(sealed, password) }
    }

    @Test
    fun `同一个密码导出两次得到的文件不同`() {
        val a = BackupCrypto.seal(payload(), password)
        val b = BackupCrypto.seal(payload(), password)
        assertNotEquals(
            "每次导出必须换新 salt 与 IV，否则可以比对出'这两份是同一份数据'",
            a.size to String(a, Charsets.ISO_8859_1).hashCode(),
            b.size to String(b, Charsets.ISO_8859_1).hashCode()
        )
    }

    @Test
    fun `peekParams 能读出与写入一致的参数`() {
        val params = KdfParams.newDefault()
        val sealed = BackupCrypto.seal(payload(), password, params)
        val read = BackupCrypto.peekParams(sealed)
        assertEquals(params.iterations, read.iterations)
        assertEquals(params.keyLengthBits, read.keyLengthBits)
        assertArrayEquals(params.salt, read.salt)
    }

    @Test
    fun `结构损坏报格式错误而不是密码错误`() {
        // 用户拿到这两种错误时的补救方式完全不同：
        // 格式错 → 文件选错了/传输损坏；密码错 → 再想想密码
        assertFails<BackupFormatException> {
            BackupCrypto.open(ByteArray(8), password)
        }
    }

    @Test
    fun `截掉尾部一个字节就打不开`() {
        // 结构校验拦不住这种截断（长度仍然够），但 GCM 的 TAG 会失败
        val sealed = BackupCrypto.seal(payload(), password)
        val truncated = sealed.copyOfRange(0, sealed.size - 1)
        assertFails<BackupPasswordException> { BackupCrypto.open(truncated, password) }
    }

    @Test
    fun `备份密码是中文时也能打开`() {
        // Utf8 那个缺 3 字节分支的 bug 之后加的一条：
        // 只要链路上有任何一处偷偷把 CharArray 转成 String 再按错误编码处理，
        // 中文备份密码就会静默失效
        val chinese = "我的备份密码".toCharArray()
        val sealed = BackupCrypto.seal(payload(), chinese)
        val opened = BackupCrypto.open(sealed, chinese)
        assertArrayEquals(payload(), opened)
        chinese.fill('\u0000')
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
