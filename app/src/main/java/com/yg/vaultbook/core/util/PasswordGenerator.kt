package com.yg.vaultbook.core.util

import java.security.SecureRandom

/**
 * 密码生成器 —— 纯逻辑，不依赖 Android，可被 JVM 单测直接覆盖。
 *
 * ── 字符集为什么这么小 ────────────────────────────────────────
 * 一律**去掉易混淆字符** `l I 1 O 0`：这些密码经常要人眼读、手抄、念给别人、
 * 或者在某台不能粘贴的设备上手动输入。少一个字符的代价是熵略降，
 * 但换来的是"不会因为看成 1 而登录失败"。
 *
 * 字符池实测：小写 25 + 大写 24 + 数字 8 + 符号 12 = 69。
 * 20 位 → 20 × log2(69) ≈ 122 bit，远超弱密码阈值 40 bit（见 [PasswordStrength]）。
 *
 * ── 为什么注入随机源 ─────────────────────────────────────────
 * 生产用 [SecureSource]（`SecureRandom`），单测塞一个固定种子的实现，
 * 否则"生成两次不同"这类断言只能靠概率，偶尔会假失败。
 */
object PasswordGenerator {

    /** 下限。低于 8 位的密码连 [PasswordStrength] 的"弱"档都过不了。 */
    const val MIN_LENGTH = 8

    /** 上限。太长的密码在多数网站的输入框里会被截断，反而更不安全。 */
    const val MAX_LENGTH = 64

    const val DEFAULT_LENGTH = 20

    /** 小写，去掉 `l`。 */
    const val LOWER = "abcdefghijkmnopqrstuvwxyz"

    /** 大写，去掉 `I` `O`。 */
    const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"

    /** 数字，去掉 `0` `1`。 */
    const val DIGITS = "23456789"

    /**
     * 符号。
     *
     * 刻意不含引号、反斜杠、空格、`$`、反引号 —— 这些在 shell 里要转义，
     * 在部分网站会被后端过滤，输进去和存下来的不一致。后面接
     * `# % ^ & * - _ = + ?` 这些"几乎不会被拒"的。
     */
    const val SYMBOLS = "!@#%^&*-_=+?"

    /** 生成参数。 */
    data class Options(
        val length: Int = DEFAULT_LENGTH,
        val lower: Boolean = true,
        val upper: Boolean = true,
        val digits: Boolean = true,
        val symbols: Boolean = true
    ) {
        /** 长度夹到 [MIN_LENGTH]..[MAX_LENGTH]。 */
        fun clamped(): Options = copy(length = length.coerceIn(MIN_LENGTH, MAX_LENGTH))

        /** 至少启用一类。全关时退回"仅小写"，而不是抛异常把 UI 弄崩。 */
        fun normalized(): Options {
            val c = clamped()
            return if (c.lower || c.upper || c.digits || c.symbols) {
                c
            } else {
                c.copy(lower = true)
            }
        }

        /** 启用了几类字符。 */
        val enabledGroups: Int
            get() = (if (lower) 1 else 0) + (if (upper) 1 else 0) +
                (if (digits) 1 else 0) + (if (symbols) 1 else 0)

        /** 字符池大小。 */
        val poolSize: Int
            get() = (if (lower) LOWER.length else 0) + (if (upper) UPPER.length else 0) +
                (if (digits) DIGITS.length else 0) + (if (symbols) SYMBOLS.length else 0)

        fun groups(): List<String> = buildList {
            if (lower) add(LOWER)
            if (upper) add(UPPER)
            if (digits) add(DIGITS)
            if (symbols) add(SYMBOLS)
        }
    }

    /** 随机源。生产用 [SecureSource]，单测注入固定序列。 */
    fun interface RandomSource {
        /** 返回 `0 until bound` 的整数。 */
        fun nextInt(bound: Int): Int
    }

    /** `SecureRandom` 包装。除了一处，全局没有别的随机数需求。 */
    object SecureSource : RandomSource {
        private val random = SecureRandom()
        override fun nextInt(bound: Int): Int = random.nextInt(bound)
    }

    /**
     * 生成密码。
     *
     * 保证：长度等于 `normalized().length`；每一类启用的字符**至少出现一次**
     * （长度不够时随机挑几类各取一个）；结果只由启用的字符组成。
     */
    fun generate(options: Options, random: RandomSource = SecureSource): String {
        val opt = options.normalized()
        val groups = opt.groups()
        val length = opt.length

        val out = CharArray(length)
        var pos = 0

        // 先给每一类各留一个位置，避免"随机出来一个纯小写"这种弱化情况。
        // 类数多于长度时（length=2 但四类全开）随机挑几个类。
        val chosen = if (groups.size <= length) groups else shuffled(groups, random).subList(0, length)
        for (group in chosen) {
            out[pos++] = group[random.nextInt(group.length)]
        }

        // 剩下的位置从整个池里取
        val pool = groups.joinToString("")
        while (pos < length) {
            out[pos++] = pool[random.nextInt(pool.length)]
        }

        // 洗牌，否则前几位永远是"小写、大写、数字、符号"这个固定顺序
        shuffleInPlace(out, random)
        return String(out)
    }

    /** 估算熵（bit）。给 UI 显示"约 xx bit 强度"用。 */
    fun entropyBits(options: Options): Double {
        val opt = options.normalized()
        val pool = opt.poolSize
        if (pool <= 1) return 0.0
        return opt.length * (ln(pool.toDouble()) / ln(2.0))
    }

    private fun ln(x: Double): Double = Math.log(x)

    private fun shuffled(list: List<String>, random: RandomSource): List<String> {
        val copy = list.toMutableList()
        for (i in copy.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val tmp = copy[i]
            copy[i] = copy[j]
            copy[j] = tmp
        }
        return copy
    }

    private fun shuffleInPlace(chars: CharArray, random: RandomSource) {
        for (i in chars.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val tmp = chars[i]
            chars[i] = chars[j]
            chars[j] = tmp
        }
    }
}
