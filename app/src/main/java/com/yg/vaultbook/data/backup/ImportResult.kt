package com.yg.vaultbook.data.backup

/**
 * 导入结果。
 *
 * 四个数字分开报，是因为它们的含义对用户完全不同：
 * - [added] 新进来的（用户最关心）
 * - [overwritten] 被备份版本盖掉的（**需要提醒**，因为本地可能改得更新）
 * - [skipped] 因为已存在而跳过的（说明为什么条数比预期少）
 * - [categoriesAdded] 顺带建的分类
 *
 * 只报一个总数的话，用户会问"我文件里 200 条，怎么只多了 150 条"。
 */
data class ImportResult(
    val added: Int,
    val overwritten: Int,
    val skipped: Int,
    val categoriesAdded: Int
) {
    /** 备份文件里一共有多少条条目被处理过 */
    val total: Int get() = added + overwritten + skipped

    val isEmpty: Boolean get() = total == 0

    /** 给 UI 直接显示的一句话。 */
    fun summary(): String = buildString {
        append("新增 ").append(added).append(" 条")
        if (overwritten > 0) append("，覆盖 ").append(overwritten).append(" 条")
        if (skipped > 0) append("，跳过 ").append(skipped).append(" 条")
        if (categoriesAdded > 0) append("，新建 ").append(categoriesAdded).append(" 个分类")
    }
}
