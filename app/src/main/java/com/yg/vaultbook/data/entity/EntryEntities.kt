package com.yg.vaultbook.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 分类。内置分类（登录/银行卡/证件/笔记）在首次建库时写入，`builtin = true`。
 *
 * 只有 `name_ct` 需要加密：分类名可能暴露"我在哪个银行有账户"这类信息。
 * `icon` / `color` 是界面配色，不含隐私，保持明文以便排序与绘制。
 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name_ct") val nameCt: ByteArray,
    @ColumnInfo(name = "icon") val icon: String? = null,
    @ColumnInfo(name = "color") val color: String? = null,
    @ColumnInfo(name = "sort") val sort: Int = 0,
    @ColumnInfo(name = "builtin") val builtin: Boolean = false
)

/**
 * 条目主表。所有 `*_ct` 字段都是 [com.yg.vaultbook.core.crypto.CryptoBlob] 密文块。
 *
 * 明文列只有两个，且都是**故意的**：
 * - `host_norm`（如 `taobao.com`）、`package_norm`（如 `com.taobao.taobao`）：
 *   自动填充要在几百毫秒内按站点查出候选条目，若这两个也加密就只能全表解密。
 *   它们只暴露"你注册过哪些站点"，不暴露账号密码。这是安全与可用性的显式取舍。
 *
 * `host_norm` / `package_norm` 上的索引**不是** DDL 里的部分索引（`WHERE deleted_at IS NULL`）：
 * Room 不支持部分索引，这里退化为普通索引。代价只是回收站里的条目也被索引到，
 * 查询侧一律带 `deleted_at IS NULL` 条件，结果集不受影响。
 */
@Entity(
    tableName = "entries",
    indices = [
        Index(value = ["host_norm"], name = "idx_entries_host"),
        Index(value = ["package_norm"], name = "idx_entries_pkg"),
        Index(value = ["updated_at"], name = "idx_entries_updated"),
        // category_id 是外键。SQLite 在删除被引用行时要反查「有没有子行指着我」，
        // 没索引就是全表扫；Room 的 @ForeignKey 也会因此报 warning。
        Index(value = ["category_id"], name = "idx_entries_category")
    ],
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class EntryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "category_id") val categoryId: String? = null,

    @ColumnInfo(name = "title_ct") val titleCt: ByteArray,
    @ColumnInfo(name = "username_ct") val usernameCt: ByteArray? = null,
    @ColumnInfo(name = "password_ct") val passwordCt: ByteArray? = null,
    @ColumnInfo(name = "url_ct") val urlCt: ByteArray? = null,
    @ColumnInfo(name = "notes_ct") val notesCt: ByteArray? = null,
    @ColumnInfo(name = "totp_ct") val totpCt: ByteArray? = null,

    @ColumnInfo(name = "host_norm") val hostNorm: String? = null,
    @ColumnInfo(name = "package_norm") val packageNorm: String? = null,

    @ColumnInfo(name = "favorite") val favorite: Boolean = false,
    /** 0..4，由 [com.yg.vaultbook.core.util.PasswordStrength] 在写入时算好，避免列表页反复算 */
    @ColumnInfo(name = "strength_score") val strengthScore: Int = 0,

    @ColumnInfo(name = "pwd_updated_at") val pwdUpdatedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long? = null,
    @ColumnInfo(name = "use_count") val useCount: Int = 0,

    /** 软删除：回收站保留 30 天，到期由清理任务物理删除 */
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null
)

/**
 * 列表页专用的投影：**不含 `notes_ct`、不含密码与 TOTP 的密文本体**。
 *
 * 为什么不用 `EntryEntity`：列表要一次性把几百条读进内存，而 `SELECT *`
 * 会把每条的备注密文（可能几 KB）、密码密文、TOTP 种子密文一起搬上来 ——
 * 这些字段在列表页一个都用不到，只需要知道"有没有密码/有没有 TOTP"。
 * 改在 SQL 里算成两个布尔，读出来的字节数就只剩标题/账号/网址三个必需字段。
 *
 * 注意：这不是 schema 变更，只是查询投影，不需要迁移；表结构仍由 [EntryEntity] 定义。
 */
data class EntrySummaryRow(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    @ColumnInfo(name = "title_ct") val titleCt: ByteArray,
    @ColumnInfo(name = "username_ct") val usernameCt: ByteArray?,
    @ColumnInfo(name = "url_ct") val urlCt: ByteArray?,
    @ColumnInfo(name = "host_norm") val hostNorm: String?,
    @ColumnInfo(name = "package_norm") val packageNorm: String?,
    @ColumnInfo(name = "favorite") val favorite: Boolean,
    @ColumnInfo(name = "strength_score") val strengthScore: Int,
    @ColumnInfo(name = "pwd_updated_at") val pwdUpdatedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long?,
    @ColumnInfo(name = "use_count") val useCount: Int,
    @ColumnInfo(name = "has_password") val hasPassword: Boolean,
    @ColumnInfo(name = "has_totp") val hasTotp: Boolean,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?
)

/**
 * 搜索备注用的投影：只有 id 和备注密文。
 *
 * 为什么要单独一个查询，而不是把 `notes_ct` 加进 [EntrySummaryRow]：
 * 备注是唯一一个"长度完全不受控"的字段（有人写两行，有人贴一整段备忘），
 * 把它挂进列表主查询，等于每次列表刷新都为所有条目的备注付一次 I/O。
 *
 * 而搜索又必须能看到备注内容（用户会写"公司邮箱别名"这种只存在于备注里的词）。
 * 折中是：列表刷新不动它，**搜索索引重建时单独拉一次**这一列。它只被
 * 解密、取词、丢掉，不会进任何 UI 状态。
 */
data class EntryNotesRow(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "notes_ct") val notesCt: ByteArray?
)

/**
 * 密码历史。只在"密码被修改"时写一行，用于误改回滚。
 *
 * 上限 20 条/条目，由 `EntryHistoryDao.trim()` 在每次插入后裁剪。
 */
@Entity(
    tableName = "entry_history",
    indices = [Index(value = ["entry_id", "changed_at"], name = "idx_hist_entry")],
    foreignKeys = [
        ForeignKey(
            entity = EntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entry_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class EntryHistoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "entry_id") val entryId: String,
    @ColumnInfo(name = "password_ct") val passwordCt: ByteArray,
    @ColumnInfo(name = "changed_at") val changedAt: Long
)

/**
 * 自定义字段（银行卡号、CVV、证件号、安全问题答案……）。
 *
 * `type` 决定 UI 是否默认打码，**不决定是否加密** —— 所有 value 一律加密。
 */
@Entity(
    tableName = "custom_fields",
    indices = [Index(value = ["entry_id"], name = "idx_cf_entry")],
    foreignKeys = [
        ForeignKey(
            entity = EntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entry_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class CustomFieldEntity(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "entry_id") val entryId: String,
    @ColumnInfo(name = "label_ct") val labelCt: ByteArray,
    @ColumnInfo(name = "value_ct") val valueCt: ByteArray,
    /** text | secret | multiline */
    @ColumnInfo(name = "type") val type: String = TYPE_TEXT,
    @ColumnInfo(name = "sort") val sort: Int = 0
) {
    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_SECRET = "secret"
        const val TYPE_MULTILINE = "multiline"
    }
}
