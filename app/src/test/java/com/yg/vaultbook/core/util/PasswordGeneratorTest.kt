package com.yg.vaultbook.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 密码生成器。
 *
 * 随机性用 [PasswordGenerator.RandomSource] 注入，好在需要确定性的地方塞固定源。
 * 涉及真随机的断言只在"必须成立"的性质上做（长度、字符集、每类至少一个），
 * 不断言具体字符串。
 */
class PasswordGeneratorTest {

    private fun allChars(o: PasswordGenerator.Options): String =
        PasswordGenerator.LOWER + PasswordGenerator.UPPER +
            PasswordGenerator.DIGITS + PasswordGenerator.SYMBOLS

    @Test
    fun `默认选项生成二十位`() {
        val pwd = PasswordGenerator.generate(PasswordGenerator.Options())
        assertEquals(PasswordGenerator.DEFAULT_LENGTH, pwd.length)
        assertEquals(20, pwd.length)
    }

    @Test
    fun `默认选项每一类至少出现一次`() {
        val pwd = PasswordGenerator.generate(PasswordGenerator.Options())
        assertTrue(pwd.any { it in PasswordGenerator.LOWER })
        assertTrue(pwd.any { it in PasswordGenerator.UPPER })
        assertTrue(pwd.any { it in PasswordGenerator.DIGITS })
        assertTrue(pwd.any { it in PasswordGenerator.SYMBOLS })
    }

    @Test
    fun `关掉的类别不会出现`() {
        val options = PasswordGenerator.Options(upper = false, symbols = false)
        repeat(20) {
            val pwd = PasswordGenerator.generate(options)
            assertTrue(pwd.all { it in PasswordGenerator.LOWER || it in PasswordGenerator.DIGITS })
        }
    }

    @Test
    fun `只开小写时结果全为小写`() {
        val options = PasswordGenerator.Options(upper = false, digits = false, symbols = false)
        val pwd = PasswordGenerator.generate(options)
        assertEquals(20, pwd.length)
        assertTrue(pwd.all { it in PasswordGenerator.LOWER })
    }

    @Test
    fun `字符池不含易混淆字符`() {
        // l I 1 O 0 会在手抄或语音报读时被认错
        val confusing = setOf('l', 'I', '1', 'O', '0')
        val pool = PasswordGenerator.LOWER + PasswordGenerator.UPPER + PasswordGenerator.DIGITS
        assertTrue(pool.none { it in confusing })
        repeat(200) {
            val pwd = PasswordGenerator.generate(PasswordGenerator.Options())
            assertTrue("生成了易混淆字符：$pwd", pwd.none { it in confusing })
        }
    }

    @Test
    fun `长度低于下界被夹到八`() {
        assertEquals(PasswordGenerator.MIN_LENGTH, PasswordGenerator.Options(length = 1).clamped().length)
        assertEquals(8, PasswordGenerator.generate(PasswordGenerator.Options(length = 1)).length)
    }

    @Test
    fun `长度高于上界被夹到六十四`() {
        assertEquals(
            PasswordGenerator.MAX_LENGTH,
            PasswordGenerator.Options(length = 999).clamped().length
        )
        assertEquals(64, PasswordGenerator.generate(PasswordGenerator.Options(length = 999)).length)
    }

    @Test
    fun `全部关闭时退回仅小写而不是抛异常`() {
        val bogus = PasswordGenerator.Options(
            lower = false, upper = false, digits = false, symbols = false
        )
        assertEquals(0, bogus.enabledGroups)
        val safe = bogus.normalized()
        assertEquals(1, safe.enabledGroups)
        assertTrue(safe.lower)
        assertEquals(PasswordGenerator.LOWER.length, safe.poolSize)
        // 生成仍然可用
        assertEquals(20, PasswordGenerator.generate(bogus).length)
    }

    @Test
    fun `池大小等于启用类别之和`() {
        val all = PasswordGenerator.Options()
        assertEquals(25 + 24 + 8 + 12, all.poolSize)
        assertEquals(69, all.poolSize)
        val onlyDigits = all.copy(lower = false, upper = false, symbols = false)
        assertEquals(8, onlyDigits.poolSize)
    }

    @Test
    fun `groups 数量等于启用类别数`() {
        assertEquals(4, PasswordGenerator.Options().groups().size)
        assertEquals(
            2,
            PasswordGenerator.Options(lower = true, upper = true, digits = false, symbols = false)
                .groups().size
        )
    }

    @Test
    fun `熵随长度增加`() {
        val short = PasswordGenerator.entropyBits(PasswordGenerator.Options(length = 12))
        val long = PasswordGenerator.entropyBits(PasswordGenerator.Options(length = 24))
        assertTrue(long > short)
        // 双倍长度 = 双倍 bit
        assertEquals(short * 2, long, 0.001)
    }

    @Test
    fun `熵随字符池增大`() {
        val narrow = PasswordGenerator.entropyBits(
            PasswordGenerator.Options(lower = true, upper = false, digits = false, symbols = false)
        )
        val wide = PasswordGenerator.entropyBits(PasswordGenerator.Options())
        assertTrue(wide > narrow)
    }

    @Test
    fun `二十位默认选项熵超过一百二十比特`() {
        val bits = PasswordGenerator.entropyBits(PasswordGenerator.Options())
        assertTrue("实际 $bits", bits > 120.0)
    }

    @Test
    fun `固定随机源可复现`() {
        // bound 恒为 0 的源：每次取字符池第一个字符
        val fixed = PasswordGenerator.RandomSource { 0 }
        val a = PasswordGenerator.generate(PasswordGenerator.Options(length = 12), fixed)
        val b = PasswordGenerator.generate(PasswordGenerator.Options(length = 12), fixed)
        assertEquals(a, b)
        assertEquals(12, a.length)
    }

    @Test
    fun `真随机源两次结果不同`() {
        // 20 位 69 池，碰撞概率约 69^-20，可以当"一定不同"
        val a = PasswordGenerator.generate(PasswordGenerator.Options())
        val b = PasswordGenerator.generate(PasswordGenerator.Options())
        assertNotEquals(a, b)
    }

    @Test
    fun `最短长度也能容纳全部四类`() {
        // MIN_LENGTH=8 > 类别数 4，所以四类各取一个是安全的；
        // 这条测试守住"以后若把 MIN_LENGTH 改到 3 以下"的边界
        assertTrue(PasswordGenerator.MIN_LENGTH >= 4)
        val pwd = PasswordGenerator.generate(PasswordGenerator.Options(length = 8))
        assertEquals(8, pwd.length)
        assertTrue(pwd.any { it in PasswordGenerator.LOWER })
        assertTrue(pwd.any { it in PasswordGenerator.UPPER })
        assertTrue(pwd.any { it in PasswordGenerator.DIGITS })
        assertTrue(pwd.any { it in PasswordGenerator.SYMBOLS })
    }

    @Test
    fun `常量取值合理`() {
        assertEquals(8, PasswordGenerator.MIN_LENGTH)
        assertEquals(64, PasswordGenerator.MAX_LENGTH)
        assertEquals(20, PasswordGenerator.DEFAULT_LENGTH)
        assertTrue(PasswordGenerator.DEFAULT_LENGTH in
            PasswordGenerator.MIN_LENGTH..PasswordGenerator.MAX_LENGTH)
        // 符号里不能有需要转义或常被过滤的字符
        listOf('"', '\'', '\\', ' ', '$', '`').forEach {
            assertFalse("符号池不该含 $it", it in PasswordGenerator.SYMBOLS)
        }
    }

    @Test
    fun `生成的字符都在池内`() {
        val options = PasswordGenerator.Options()
        val allowed = allChars(options).toSet()
        repeat(50) {
            val pwd = PasswordGenerator.generate(options)
            pwd.forEach { ch ->
                assertTrue("出现池外字符 $ch", ch in allowed)
            }
        }
    }

    @Test
    fun `熵下限与位数的关系`() {
        // 8 位最小池（25 个小写）≈ 37.15 bit，低于 40 bit 的弱密码阈值
        val bits = PasswordGenerator.entropyBits(
            PasswordGenerator.Options(
                length = 8, lower = true, upper = false, digits = false, symbols = false
            )
        )
        assertEquals(8 * 4.643856, bits, 0.01)
        assertTrue(bits < 40.0)
    }
}
