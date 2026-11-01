package com.yg.vaultbook.data

/**
 * 安全体检的结果模型。
 *
 * 体检只在用户点进来时跑一遍：把全库密码解出来比一比，算完立刻清零。
 * 不做成订阅 —— 这活要解密全部密码，常驻跑一次就是白烧电。
 */
enum class SecurityIssueKind { WEAK, REUSED, OLD, NO_PASSWORD }

/** 一条问题。entryId 用来「点进去改」，其余字段只为了让人一眼认出是哪一条。 */
data class SecurityIssue(
    val entryId: String,
    val title: String,
    val username: String?,
    val kind: SecurityIssueKind,
    val detail: String
)

data class SecurityReport(
    val total: Int,
    val withPassword: Int,
    val issues: List<SecurityIssue>
) {
    val isEmpty: Boolean get() = issues.isEmpty()

    fun count(kind: SecurityIssueKind): Int = issues.count { it.kind == kind }

    fun issuesOf(kind: SecurityIssueKind): List<SecurityIssue> = issues.filter { it.kind == kind }

    /** 一句话总结，给设置页做副标题用。 */
    fun headline(): String = when {
        total == 0 -> "还没有条目"
        isEmpty -> "体检通过，没发现问题"
        else -> "${issues.size} 处可以改"
    }
}

/** 密码超过这么久没换，体检里会提一句。180 天是「一年换一次」的宽松版。 */
const val OLD_PASSWORD_DAYS = 180