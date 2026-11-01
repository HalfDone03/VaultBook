package com.yg.vaultbook.core.util

import kotlin.math.ln

/**
 * 密码强度评估。
 *
 * 用**字符集熵**而不是"有没有大写字母/数字/符号"的三段式打勾法。
 * 后者会告诉你 `P@ssw0rd1` 很强（实际上在任何字典攻击里排前 1000），
 * 而 `correct horse battery staple` 很弱（实际上约 100 位熵）。
 *
 * 熵 = 长度 × log2(字符池大小)，再对几类典型的伪强度做惩罚：
 * - 命中内置常见口令表 → 直接 0 分
 * - 单字符重复（`aaaaaaaa`）→ 直接 0 分
 * - 键盘序列 / 递增序列（`qwerty`、`123456`）→ 降两级
 *
 * ⚠️ 局限：这是"用户自己判断"的辅助信息，不是防破解承诺。
 * 真正决定安全的是**这条密码有没有在别的站重复使用** —— 那需要泄露检测（P2，HIBP k-Anonymity）。
 */
object PasswordStrength {

    /** 低于此熵视为弱密码，与 docs/01 的业务阈值表一致 */
    const val WEAK_ENTROPY_BITS: Double = 40.0

    /** 0 = 很弱，4 = 很强 */
    const val MAX_SCORE: Int = 4

    private const val POOL_LOWER = 26
    private const val POOL_UPPER = 26
    private const val POOL_DIGIT = 10
    private const val POOL_SYMBOL = 33
    /** 非 ASCII（中文、日文、emoji 等）：粗估，不高估它的强度 */
    private const val POOL_OTHER = 100

    /**
     * 常见口令表。不做哈希、不做布隆过滤器 —— 它是几十条字符串，
     * 直接 `contains` 比任何索引都快，也不会因为哈希碰撞误判。
     */
    private val COMMON_PASSWORDS = setOf(
        "123456", "12345678", "123456789", "1234567890", "1234567", "12345",
        "password", "passw0rd", "p@ssw0rd", "password1", "password123",
        "qwerty", "qwerty123", "qwertyuiop", "asdfgh", "asdfghjkl", "zxcvbnm",
        "111111", "000000", "666666", "888888", "123123", "112233", "121212",
        "abc123", "abcd1234", "a123456", "1q2w3e4r", "1qaz2wsx", "qazwsx",
        "iloveyou", "admin", "administrator", "root", "letmein", "welcome",
        "monkey", "dragon", "sunshine", "princess", "football", "login",
        "woaini", "5201314", "woaini1314", "520520", "1314520"
    )

    private val KEYBOARD_RUNS = listOf(
        "qwertyuiop", "asdfghjkl", "zxcvbnm", "1234567890", "abcdefghijklmnopqrstuvwxyz"
    )

    /**
     * 估算熵（比特）。
     *
     * @param chars 密码字符。调用方负责在用完后 `fill('\u0000')`
     */
    fun entropyBits(chars: CharArray): Double {
        if (chars.isEmpty()) return 0.0

        var hasLower = false
        var hasUpper = false
        var hasDigit = false
        var hasSymbol = false
        var hasOther = false

        for (c in chars) {
            when {
                c in 'a'..'z' -> hasLower = true
                c in 'A'..'Z' -> hasUpper = true
                c in '0'..'9' -> hasDigit = true
                c.code in 0x20..0x7E -> hasSymbol = true
                else -> hasOther = true
            }
        }

        var pool = 0
        if (hasLower) pool += POOL_LOWER
        if (hasUpper) pool += POOL_UPPER
        if (hasDigit) pool += POOL_DIGIT
        if (hasSymbol) pool += POOL_SYMBOL
        if (hasOther) pool += POOL_OTHER

        if (pool <= 0) return 0.0

        return chars.size * log2(pool.toDouble())
    }

    /** 0..4 分。见 [labelOf] 获取文案。 */
    fun score(chars: CharArray): Int {
        if (chars.isEmpty()) return 0

        if (isCommon(chars) || isSingleRepeated(chars)) return 0

        val bits = entropyBits(chars)
        var level = when {
            bits >= 80.0 -> 4
            bits >= 60.0 -> 3
            bits >= WEAK_ENTROPY_BITS -> 2
            bits >= 28.0 -> 1
            else -> 0
        }

        // 纯键盘序列 / 递增序列先扣分，再叠加长度优势没有意义
        if (isSequence(chars)) level = (level - 2).coerceAtLeast(0)

        return level.coerceIn(0, MAX_SCORE)
    }

    fun isWeak(chars: CharArray): Boolean = score(chars) <= 1

    fun labelOf(score: Int): String = when (score.coerceIn(0, MAX_SCORE)) {
        0 -> "很弱"
        1 -> "弱"
        2 -> "一般"
        3 -> "强"
        else -> "很强"
    }

    // ── 内部判定 ──────────────────────────────────────────────────

    private fun isCommon(chars: CharArray): Boolean {
        if (chars.size > 32) return false
        val s = String(chars)
        return COMMON_PASSWORDS.contains(s.lowercase())
    }

    private fun isSingleRepeated(chars: CharArray): Boolean {
        val first = chars[0]
        return chars.all { it == first }
    }

    /** 是否整串都是键盘序列或递增序列的一段（含反向） */
    private fun isSequence(chars: CharArray): Boolean {
        if (chars.size < 4) return false
        val s = String(chars).lowercase()
        val reversed = s.reversed()

        for (run in KEYBOARD_RUNS) {
            if (run.contains(s) || run.contains(reversed)) return true
        }

        // 纯数字递增/递减，如 13579 / 246810
        val digits = s.filter { it in '0'..'9' }
        if (digits.length == s.length && s.length >= 4) {
            val step = s[1].code - s[0].code
            if (step == 1 || step == -1 || step == 2 || step == -2) {
                var ok = true
                for (i in 2 until s.length) {
                    if (s[i].code - s[i - 1].code != step) { ok = false; break }
                }
                if (ok) return true
            }
        }
        return false
    }

    private fun log2(x: Double): Double = ln(x) / LN2

    private val LN2: Double = ln(2.0)
}
