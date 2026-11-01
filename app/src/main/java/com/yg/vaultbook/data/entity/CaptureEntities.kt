package com.yg.vaultbook.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 站点 → 条目的学习映射。
 *
 * 自动填充时先查这张表：用户上次在这个站点选了哪条记录，下次优先给这条。
 * `weight` 随"用户主动选择"累加，用来在多个候选里排序 —— 没有它就只能永远按更新时间排序。
 *
 * 唯一索引在 SQLite 语义下对 NULL 不生效（NULL != NULL），
 * 因此当 `host_norm` 为 NULL 时不会被拦，正好等价于 DDL 里的部分索引意图。
 */
@Entity(
    tableName = "site_mappings",
    indices = [
        Index(value = ["host_norm", "entry_id"], unique = true, name = "idx_map_host"),
        Index(value = ["package_norm", "entry_id"], unique = true, name = "idx_map_pkg"),
        // entry_id 是外键，删条目时要按它反查映射行（CASCADE 也需要它才不扫全表）。
        // 注意 idx_map_host 的首列是 host_norm，不能替代这里的单列索引。
        Index(value = ["entry_id"], name = "idx_map_entry")
    ],
    foreignKeys = [
        ForeignKey(
            entity = EntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entry_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class SiteMappingEntity(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "host_norm") val hostNorm: String? = null,
    @ColumnInfo(name = "package_norm") val packageNorm: String? = null,
    @ColumnInfo(name = "entry_id") val entryId: String,
    @ColumnInfo(name = "weight") val weight: Int = 1,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long
)

/**
 * 待确认的捕获。抓取通道把候选写进这里，等用户点"保存"才转成正式条目。
 *
 * 为什么必须落库而不是纯内存：
 * 用户可能在别的 App 里登录完就切走了，本进程随时可能被系统杀掉。
 * 内存缓冲（TTL 90s）负责"同一次登录去重"，这张表负责"跨进程不丢"。
 *
 * **明文存储的是密文块**：`title_ct` 等都是 CryptoBlob，
 * 因此即使表被导出也读不出内容。
 */
@Entity(
    tableName = "pending_captures",
    indices = [
        Index(value = ["state", "created_at"], name = "idx_pending_state"),
        Index(value = ["host_norm"], name = "idx_pending_host")
    ]
)
data class PendingCaptureEntity(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "host_norm") val hostNorm: String? = null,
    @ColumnInfo(name = "package_norm") val packageNorm: String? = null,
    @ColumnInfo(name = "title_ct") val titleCt: ByteArray? = null,
    @ColumnInfo(name = "username_ct") val usernameCt: ByteArray? = null,
    @ColumnInfo(name = "password_ct") val passwordCt: ByteArray? = null,
    /** 0.0..1.0，见 docs/02 §5.4 的置信度公式 */
    @ColumnInfo(name = "confidence") val confidence: Float,
    /** autofill | a11y | webview */
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "matched_entry_id") val matchedEntryId: String? = null,
    /** pending | accepted | dismissed */
    @ColumnInfo(name = "state") val state: String = STATE_PENDING,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** created_at + 7d */
    @ColumnInfo(name = "expires_at") val expiresAt: Long
) {
    companion object {
        const val STATE_PENDING = "pending"
        const val STATE_ACCEPTED = "accepted"
        const val STATE_DISMISSED = "dismissed"

        const val SOURCE_AUTOFILL = "autofill"
        const val SOURCE_A11Y = "a11y"
        const val SOURCE_WEBVIEW = "webview"

        const val TTL_MS: Long = 7L * 24 * 60 * 60 * 1000
    }
}

/**
 * "这个站点别再问我了"。按 host 静默 24 小时。
 *
 * 单独建表而不是塞进 site_mappings：静默是临时状态，
 * 到期要自动失效，和"用户主动选择过哪条"是两件事。
 */
@Entity(tableName = "dismiss_rules")
data class DismissRuleEntity(
    @PrimaryKey
    @ColumnInfo(name = "host_norm") val hostNorm: String,
    @ColumnInfo(name = "until_at") val untilAt: Long
)
