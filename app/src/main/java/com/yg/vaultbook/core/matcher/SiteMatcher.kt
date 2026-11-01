package com.yg.vaultbook.core.matcher

/**
 * 候选排序结果。
 *
 * 只带 `entryId`，**不带任何明文** —— 自动填充服务的 `onFillRequest` 会在锁定时被系统调用，
 * 这个对象可能被临时持有在 Autofill 框架进程里，绝不能把密码塞进去。
 */
data class MatchCandidate(
    val entryId: String,
    val score: Int,
    /** 为什么排在前面，用于调试与将来在 UI 上解释"为什么推荐这条" */
    val reasons: List<String>
)

/**
 * 一条条目与本次请求的"关系"，由调用方（Repository）查库后填。
 *
 * 拆成这种纯数据输入是为了让排序逻辑能在 JVM 单测里跑 —— 不需要 Room、不需要真机。
 */
data class RankInput(
    val entryId: String,
    /** `site_mappings` 里存在 host→entry 的映射（用户曾在这个站点主动选过它） */
    val mapped: Boolean = false,
    /** 条目的 host_norm 与本次请求的 host 完全一致 */
    val hostExact: Boolean = false,
    /** 条目的 package_norm 与本次请求的包名完全一致 */
    val packageExact: Boolean = false,
    /** 条目的 host 是本次 host 的后缀（如条目存 `taobao.com`、请求来自 `h5.taobao.com`） */
    val hostSuffix: Boolean = false,
    /** 页面已经把用户名填好了，且与该条目用户名相同 */
    val usernameMatches: Boolean = false,
    val favorite: Boolean = false,
    val useCount: Int = 0,
    val lastUsedAt: Long? = null,
    val hasPassword: Boolean = true
)

/**
 * 自动填充候选排序。
 *
 * ── 为什么需要打分而不是简单"按 host 查出来就完了" ──────────
 * 一个站点常有多个条目（比如同一家银行的两个账号），同一条目也可能挂在多个站点
 * （比如 Google 账号登录一堆第三方站点）。只按更新时间排序的结果是：
 * 用户刚编辑过的那条总是排第一，哪怕上一次在某站点用的是另一条。
 *
 * `site_mappings` 记录的"这个站点上次用了哪条"是最强信号 —— 它来自用户的真实选择，
 * 而不是我们的猜测。所以它的权重远高于其他所有信号。
 *
 * ── 分数设计 ───────────────────────────────────────────────
 * 基础分（取最高的一项，不叠加）：
 * - 映射命中                    100
 * - host 完全一致                80
 * - 包名完全一致                70
 * - host 后缀一致               45
 *
 * 加分：
 * - 用户名与页面已填内容相同      +25
 * - 收藏                          +6
 * - 最近 30 天内用过             +10（30 天内线性衰减到 0）
 *
 * 减分：
 * - 没有密码                     -40（出现在填充列表里但点下去什么也填不上，是坏体验）
 *
 * 门槛：低于 [MIN_SCORE] 直接丢弃，宁可不推荐也不要推荐错的。
 */
object SiteMatcher {

    const val MAX_CANDIDATES = 5
    const val MIN_SCORE = 30

    private const val BASE_MAPPED = 100
    private const val BASE_HOST_EXACT = 80
    private const val BASE_PACKAGE_EXACT = 70
    private const val BASE_HOST_SUFFIX = 45

    private const val BONUS_USERNAME_MATCH = 25
    private const val BONUS_FAVORITE = 6
    private const val BONUS_RECENT_MAX = 10
    private const val PENALTY_NO_PASSWORD = 40

    private const val RECENT_WINDOW_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * 排序并截断。
     *
     * 同一 `entryId` 出现多次时按"最佳那次"计分（调用方可能因为 host 和后缀各查了一次）。
     */
    fun rank(inputs: List<RankInput>, now: Long): List<MatchCandidate> {
        val best = LinkedHashMap<String, Scored>()

        for (input in inputs) {
            val (score, reasons) = score(input, now)
            val previous = best[input.entryId]
            if (previous == null || score > previous.score) {
                best[input.entryId] = Scored(score, input.lastUsedAt ?: 0L, reasons)
            }
        }

        return best.entries
            .filter { it.value.score >= MIN_SCORE }
            .sortedWith(
                compareByDescending<Map.Entry<String, Scored>> { it.value.score }
                    // 同分时让最近用过的靠前；再相同用 id 保证顺序稳定（结果可复现，便于测试与排错）
                    .thenByDescending { it.value.lastUsed }
                    .thenBy { it.key }
            )
            .take(MAX_CANDIDATES)
            .map { (entryId, value) ->
                MatchCandidate(entryId = entryId, score = value.score, reasons = value.reasons)
            }
    }

    private class Scored(val score: Int, val lastUsed: Long, val reasons: List<String>)

    private fun score(input: RankInput, now: Long): Pair<Int, List<String>> {
        var score = 0
        val reasons = mutableListOf<String>()

        when {
            input.mapped -> {
                score += BASE_MAPPED
                reasons += "这个站点上次用的就是它"
            }
            input.hostExact -> {
                score += BASE_HOST_EXACT
                reasons += "网址完全匹配"
            }
            input.packageExact -> {
                score += BASE_PACKAGE_EXACT
                reasons += "应用匹配"
            }
            input.hostSuffix -> {
                score += BASE_HOST_SUFFIX
                reasons += "网址后缀匹配"
            }
        }

        if (input.usernameMatches) {
            score += BONUS_USERNAME_MATCH
            reasons += "账号与页面已填内容一致"
        }

        if (input.favorite) {
            score += BONUS_FAVORITE
            reasons += "已收藏"
        }

        val recent = recentBonus(input.lastUsedAt, now)
        if (recent > 0) {
            score += recent
            reasons += "最近用过"
        }

        if (!input.hasPassword) {
            score -= PENALTY_NO_PASSWORD
            reasons += "没有密码"
        }

        return score to reasons
    }

    /** 最近 30 天内用过则加分，随天数线性衰减。 */
    private fun recentBonus(lastUsedAt: Long?, now: Long): Int {
        if (lastUsedAt == null || lastUsedAt <= 0L) return 0
        val age = now - lastUsedAt
        if (age < 0L) return BONUS_RECENT_MAX      // 时钟回拨：按"刚用过"处理
        if (age >= RECENT_WINDOW_MS) return 0
        val ratio = 1.0 - age.toDouble() / RECENT_WINDOW_MS
        return (BONUS_RECENT_MAX * ratio).toInt()
    }

    /**
     * 判断页面里已填的账号与条目账号是否同一身份。
     *
     * 不区分大小写、忽略首尾空白。**不做模糊匹配**：
     * `abc` 与 `abcd` 不是同一个账号，猜错会让用户把 A 账号的密码填进 B 账号的登录框。
     */
    fun sameUsername(a: String?, b: String?): Boolean {
        val left = a?.trim()?.lowercase()
        val right = b?.trim()?.lowercase()
        if (left.isNullOrEmpty() || right.isNullOrEmpty()) return false
        return left == right
    }
}
