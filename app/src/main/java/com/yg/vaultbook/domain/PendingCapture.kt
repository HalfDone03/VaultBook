package com.yg.vaultbook.domain

/**
 * 待确认捕获的列表视图：**不含密码**。
 *
 * 列表页要一次显示几十条待确认记录，把每条密码都解密进内存会重演 W1 里
 * "列表不预加载密码"的妥协。这里只给 `hasPassword` 布尔。
 */
data class PendingCaptureSummary(
    val id: String,
    val hostNorm: String?,
    val packageNorm: String?,
    /** 站点标题（已解密，可能为空） */
    val title: String?,
    /** 账号（已解密，可能为空） */
    val username: String?,
    /** 0.0..1.0，见 [com.yg.vaultbook.core.matcher.CaptureConfidence] */
    val confidence: Float,
    /** autofill | a11y | webview */
    val source: String,
    /** 如果这次登录是在某个已知条目上做的，记下它，保存时默认更新这条而不是新建 */
    val matchedEntryId: String?,
    val createdAt: Long,
    val hasPassword: Boolean
) {
    /** 展示用：域名优先，其次包名，都没有就显示"未知站点" */
    fun siteLabel(): String = hostNorm ?: packageNorm ?: "未知站点"
}

/**
 * 一条待落库的捕获。
 *
 * [password] 是 `CharArray` —— 与全局约定一致，密码不进 `String`。
 * 调用方用完必须调 [clearSecrets]。
 */
data class CaptureInput(
    /** 已归一化的 host（`taobao.com`），凭浏览器上报的真实域名。绝不猜。 */
    val hostNorm: String? = null,
    /** 前台应用包名（非浏览器场景） */
    val packageNorm: String? = null,
    val title: String? = null,
    val username: String? = null,
    val password: CharArray? = null,
    /** [PendingCaptureEntity.SOURCE_AUTOFILL] 等 */
    val source: String,
    val confidence: Float,
    val matchedEntryId: String? = null
) {
    fun clearSecrets() {
        password?.fill('\u0000')
    }
}

/** 落库结果。 */
sealed interface CaptureRecordResult {
    /** 新建了一条待确认记录 */
    data class Added(val id: String) : CaptureRecordResult

    /** 命中去重窗口，把密码更新到已有记录上 */
    data class Updated(val id: String) : CaptureRecordResult

    /** 没存，附带原因（用于日志与调试，不展示给用户） */
    data class Skipped(val reason: String) : CaptureRecordResult
}
