package com.yg.vaultbook.domain

/**
 * 列表页用的条目视图：**不含密码**。
 *
 * 为什么单独分一层而不是直接复用实体 + 解密后的大对象：
 * 列表页会一次性加载几百条，如果每条都把密码解密进内存并常驻，
 * 那"密码只在点显示时解密"这条设计就废了。这里 `hasPassword` 只是一个布尔，
 * 靠"解出来的密码长度是否为零"推导出来 —— 不需要保留字符。
 *
 * 所有 `String` 字段都是**已解密**的明文（标题、账号、网址）。
 * 它们本来就要显示在屏幕上，加密只针对落盘。
 */
data class EntrySummary(
    val id: String,
    val categoryId: String?,
    val title: String,
    val username: String?,
    val url: String?,
    val hostNorm: String?,
    val packageNorm: String?,
    val favorite: Boolean,
    /** 0..4 */
    val strengthScore: Int,
    val pwdUpdatedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long?,
    val useCount: Int,
    val hasPassword: Boolean,
    val hasTotp: Boolean,
    /**
     * 进回收站的时间。`null` = 还活着。
     *
     * 加在最后并带默认值，是为了不破坏既有构造点（`toSummary` 之外的调用方
     * 大多是测试里的手工构造）。回收站页面靠它算"还剩几天"。
     */
    val deletedAt: Long? = null
) {
    /** 供搜索使用的归一化文本。调用方用 [com.yg.vaultbook.core.util.Normalize.forSearch] 生成。 */
    fun searchText(): String = buildString {
        append(title)
        username?.let { append('\u0001').append(it) }
        url?.let { append('\u0001').append(it) }
        hostNorm?.let { append('\u0001').append(it) }
    }
}

/** 自定义字段（已解密）。 */
data class PlainCustomField(
    val id: String,
    val label: String,
    val value: String,
    /** text | secret | multiline */
    val type: String,
    val sort: Int
)

/** 详情页使用的完整视图：含备注、TOTP 种子、自定义字段，**仍不含密码**。 */
data class EntryDetail(
    val summary: EntrySummary,
    val notes: String?,
    val totp: String?,
    val customFields: List<PlainCustomField>
)

/** 编辑页提交的草稿。 */
data class EntryDraft(
    val title: String,
    val username: String? = null,
    /**
     * 新密码。
     *
     * - `null`：不修改（编辑已有条目时保留原密码；新建时表示"没有密码"）
     * - 非空：写入新密码，并把旧密码推进历史
     *
     * 暂不支持"把密码清空成 null" —— 需要清空就删掉条目重建。
     * 加一个第三态（Keep / Set / Clear）会让调用方每次都得想清楚，
     * 而真实使用频率极低。
     */
    val password: CharArray? = null,
    val url: String? = null,
    val notes: String? = null,
    val totp: String? = null,
    val categoryId: String? = null,
    val favorite: Boolean = false,
    val customFields: List<CustomFieldDraft> = emptyList()
) {
    /** 草稿里可能带密码，用完必须清掉。 */
    fun clearSecrets() {
        password?.fill('\u0000')
    }
}

/**
 * 自定义字段草稿。
 *
 * `id` 必须**跨次编辑保持稳定**：自定义字段的密文 AAD 绑的是 `字段id|fieldName`，
 * 如果每次保存都换新 id，旧密文就再也解不开了（会报完整性失败）。
 * 新建字段传 null，由仓储层生成。
 */
data class CustomFieldDraft(
    val id: String? = null,
    val label: String,
    val value: String,
    val type: String = "text"
)

/** 分类（已解密）。 */
data class PlainCategory(
    val id: String,
    val name: String,
    val icon: String?,
    val color: String?,
    val sort: Int,
    val builtin: Boolean
)
