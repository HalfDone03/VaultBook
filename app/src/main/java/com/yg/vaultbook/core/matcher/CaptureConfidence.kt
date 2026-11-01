package com.yg.vaultbook.core.matcher

/** 对一次捕获的处理建议。 */
enum class CaptureVerdict {
    /** 证据不足，直接丢弃，连"待确认"都不进 —— 宁可漏存也不要让用户面对一堆垃圾候选 */
    DISCARD,

    /** 进待确认列表，等用户点保存 */
    PENDING,

    /** 证据充分，可以在页面上直接弹一条"要保存吗"的确认条 */
    STRONG
}

/**
 * 捕获置信度。
 *
 * ── 为什么需要打分而不是"看到密码框就存" ────────────────────
 * 无障碍通道看到的是一棵节点树，它无法区分"登录表单"和"注册表单"、
 * 也无法区分"改密码"和"首次设置密码"。如果见到密码框就弹提示，
 * 用户很快会把整个功能关掉。
 *
 * 所以这里给每条信号一个权重，加起来作为"这真的是一次登录"的信心：
 *
 * | 信号 | 权重 | 理由 |
 * |---|---|---|
 * | 有密码框 | 0.35 | 没有它就不是登录 |
 * | 有账号框 | 0.25 | 只有密码框的通常是"确认密码"或支付密码 |
 * | 能确定站点身份 | 0.20 | 没有域名/包名，存下来也无从匹配 |
 * | 检测到提交动作 | 0.10 | 点了按钮才是"完成登录" |
 * | 页面标题非空 | 0.10 | 弱信号，用来区分有效页面与空白/错误页 |
 *
 * 满分 1.0。[STRONG_THRESHOLD] 以上直接弹确认条；[PENDING_THRESHOLD] 到它之间进待确认列表；
 * 再低就丢弃。
 *
 * ── 两个阈值的含义差别 ─────────────────────────────────────
 * - 高于 STRONG：证据太明显了，立刻打扰用户是值得的
 * - 介于两者之间：可能对，但不够确定，静默放进待确认列表，用户想起来再看
 *
 * 定这两个数字时偏保守：漏存一条用户还能手动补，错存一条会污染"这个站点用哪个账号"的学习结果。
 */
object CaptureConfidence {

    const val STRONG_THRESHOLD = 0.85f
    const val PENDING_THRESHOLD = 0.60f

    private const val W_PASSWORD = 0.35f
    private const val W_USERNAME = 0.25f
    private const val W_SITE = 0.20f
    private const val W_SUBMIT = 0.10f
    private const val W_TITLE = 0.10f

    /**
     * @param hasPassword 页面里识别到密码字段
     * @param hasUsername 页面里识别到账号字段
     * @param hasSiteIdentity 能确定站点身份（浏览器给了真实域名，或前台应用包名可用）
     * @param submitted 检测到提交动作（点了登录按钮 / 页面导航 / 表单消失）
     * @param hasTitle 页面标题或窗口标题非空
     */
    fun score(
        hasPassword: Boolean,
        hasUsername: Boolean,
        hasSiteIdentity: Boolean,
        submitted: Boolean,
        hasTitle: Boolean
    ): Float {
        var s = 0f
        if (hasPassword) s += W_PASSWORD
        if (hasUsername) s += W_USERNAME
        if (hasSiteIdentity) s += W_SITE
        if (submitted) s += W_SUBMIT
        if (hasTitle) s += W_TITLE
        // 浮点累加会出现 0.8500001 这种，统一收敛到两位小数，
        // 否则阈值边界上的判断会随机翻转（测试也会变得不可复现）
        return (Math.round(s * 100f) / 100f).coerceIn(0f, 1f)
    }

    fun verdict(score: Float): CaptureVerdict = when {
        score >= STRONG_THRESHOLD -> CaptureVerdict.STRONG
        score >= PENDING_THRESHOLD -> CaptureVerdict.PENDING
        else -> CaptureVerdict.DISCARD
    }
}
