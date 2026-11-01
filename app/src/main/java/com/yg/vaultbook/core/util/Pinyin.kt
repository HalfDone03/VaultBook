package com.yg.vaultbook.core.util

import java.nio.charset.Charset

/**
 * 汉字拼音首字母。
 *
 * ── 为什么需要它 ────────────────────────────────────────────
 * 中文用户的心理模型是"淘宝"→`tb`、"支付宝"→`zfb`，而不是把标题
 * 一个字一个字打出来。在此之前搜 `zfb` 一无所获，只能退回打全名。
 *
 * ── 数据从哪来（这里没有任何手写词库）──────────────────────
 * GB2312 的**一级汉字区**（0xB0A1–0xD7F9）恰好是**按拼音字母序排列**的，
 * 共 3755 个字。也就是说，只要知道 23 个声母各自的起始偏移，
 * 任意一级汉字的首字母就能由它的区位码 O(1) 算出：
 *
 * ```
 * 区位索引 = (hi - 0xB0) * 94 + (lo - 0xA1)
 * 在 BLOCK_STARTS 里二分，落在哪一段就是哪个声母
 * ```
 *
 * `BLOCK_STARTS` 的 24 个数字是用脚本遍历 3755 个字、以 23 个锚点字
 * （啊/八/擦/搭/蛾/发/噶/哈/击/喀/垃/妈/拿/哦/啪/期/然/撒/塌/挖/昔/压/匝）
 * 定位出来的，并逐块核对了首尾汉字（如 `x` 区起点是「昔熙析」、
 * `z` 区终点是「作坐座」）。**这张表不需要维护，除非改拼音标准。**
 *
 * ── 三条明确的边界 ─────────────────────────────────────────
 * 1. **只有首字母，没有全拼**。搜 `zfb` 或 `zf` 命中「支付宝」，
 *    但搜 `zhifubao` 不命中 —— 全拼需要 3755 条完整拼音，那是另一个量级的
 *    数据，而首字母已经覆盖绝大多数输入习惯。
 * 2. **只认 GB2312 一级汉字**。生僻字（如「燚」）、繁体、日文汉字返回
 *    [NONE]，它们仍然按原文参与匹配，只是没有首字母。
 * 3. **多音字取 GB2312 排的那个音**。「行」归到 x（xing）而不是 h（hang），
 *    「重」归到 z（zhong）而不是 c（chong）。这类字搜另一个音会落空 ——
 *    但用户总能用原文或其他字段搜到。
 */
object Pinyin {

    /** 23 个声母，顺序必须与 [BLOCK_STARTS] 对齐（GB2312 里没有 i/u/v 声母）。 */
    private const val ALPHABET = "abcdefghjklmnopqrstwxyz"

    /**
     * 每个声母在一级汉字区里的起始偏移，末尾补上总数 3755 作为右边界。
     * 与 [ALPHABET] 一一对应。
     */
    private val BLOCK_STARTS = intArrayOf(
        0,      // a
        42,     // b
        220,    // c
        453,    // d
        637,    // e
        659,    // f
        784,    // g
        939,    // h
        1120,   // j
        1415,   // k
        1515,   // l
        1763,   // m
        1914,   // n
        1995,   // o
        2003,   // p
        2125,   // q
        2282,   // r
        2341,   // s
        2627,   // t
        2783,   // w
        2903,   // x
        3126,   // y
        3432,   // z
        3755    // 一级汉字区结束
    )

    /** 无首字母（非一级汉字、非 ASCII）时的返回值。 */
    const val NONE: Char = '\u0000'

    /**
     * GB2312 解码器。
     *
     * 取不到时（理论上 Android 都有）整个功能降级为"没有首字母"，
     * 不影响原文搜索 —— 绝不能因为一个锦上添花的匹配方式让搜索崩掉。
     */
    private val gb2312: Charset? =
        runCatching { Charset.forName("GB2312") }.getOrNull()

    /**
     * 字 → 首字母的缓存。
     *
     * 编码一整个字符串会分配数组，而列表索引每次增删都要重算一遍，
     * 同一个字会被反复问几百次。缓存后每个字只编一次。
     * 不设上限：常用汉字总量就那么多，峰值几千项。
     */
    private val cache = HashMap<Char, Char>(4096)

    /**
     * 单个字符的首字母（小写）。
     *
     * ASCII 字母数字原样返回（小写化），这样 `JD` 也能被 `jd` 搜到；
     * 其余非汉字返回 [NONE]。
     */
    fun initialOf(ch: Char): Char {
        if (ch.code < 0x80) {
            return if (ch.isLetterOrDigit()) ch.lowercaseChar() else NONE
        }
        cache[ch]?.let { return it }
        val computed = computeInitial(ch)
        cache[ch] = computed
        return computed
    }

    private fun computeInitial(ch: Char): Char {
        val charset = gb2312 ?: return NONE

        val bytes = try {
            ch.toString().toByteArray(charset)
        } catch (_: Exception) {
            // 无法编码：要么不在 GB2312，要么编码器拒绝了这个码位
            return NONE
        }
        if (bytes.size != 2) return NONE

        val hi = bytes[0].toInt() and 0xFF
        val lo = bytes[1].toInt() and 0xFF
        // 一级汉字区之外（二级汉字 0xD8A1 起、符号区等）一律不处理
        if (hi < 0xB0 || hi > 0xD7) return NONE
        if (lo < 0xA1 || lo > 0xFE) return NONE

        val index = (hi - 0xB0) * 94 + (lo - 0xA1)
        if (index < 0 || index >= 3755) return NONE

        // 二分找最后一个 BLOCK_STARTS[n] <= index
        var low = 0
        var high = ALPHABET.length - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (BLOCK_STARTS[mid] <= index) low = mid else high = mid - 1
        }
        return ALPHABET[low]
    }

    /**
     * 整段文本的首字母串，只保留有首字母的字符。
     *
     * 例：「支付宝」→`zfb`、「Wi-Fi」→`wifi`、「淘宝 5」→`tb5`。
     * 全角字母已经由 [Normalize.forSearch] 转过半角，这里不再处理。
     */
    fun initials(text: String): String {
        if (text.isEmpty()) return ""
        val out = StringBuilder(text.length)
        for (ch in text) {
            val initial = initialOf(ch)
            if (initial != NONE) out.append(initial)
        }
        return out.toString()
    }

    /**
     * 给搜索索引用的一整条串：**归一化原文 + 空格 + 首字母串**。
     *
     * 用空格分隔而不是直接拼，是为了避免跨字段意外拼出命中
     * （`abc` 和 `def` 拼成 `abcdef` 会让搜 `cde` 命中一条无关记录）。
     * 这与 [com.yg.vaultbook.domain.EntrySummary.searchText] 用 `\u0001`
     * 分隔各字段是同一个理由。
     *
     * 首字母串为空时直接返回原文，省下一个尾随空格。
     */
    fun searchKey(raw: String): String {
        val normalized = Normalize.forSearch(raw)
        val initials = initials(normalized)
        return when {
            initials.isEmpty() -> normalized
            normalized.isEmpty() -> initials
            else -> "$normalized $initials"
        }
    }
}
