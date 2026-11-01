package com.yg.vaultbook.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 审计日志。记录"谁在什么时候看了/复制了/删除了哪条"，用于自查异常。
 *
 * 硬约束：`detail` **绝不能出现明文凭据**。
 * 允许写"字段名 + 动作"，例如 `detail = "revealed password"`；
 * 不允许写密码本身、也不允许写完整账号。
 *
 * 上限 2000 条，由 `AuditLogDao.trim()` 在插入后裁剪。
 */
@Entity(
    tableName = "audit_log",
    indices = [Index(value = ["created_at"], name = "idx_audit_time")]
)
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    /** unlock_ok | unlock_fail | lock | copy | reveal | create | update | delete | export | import */
    @ColumnInfo(name = "event") val event: String,
    @ColumnInfo(name = "target_id") val targetId: String? = null,
    @ColumnInfo(name = "detail") val detail: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long
) {
    companion object {
        const val EVENT_UNLOCK_OK = "unlock_ok"
        const val EVENT_UNLOCK_FAIL = "unlock_fail"
        const val EVENT_LOCK = "lock"
        const val EVENT_COPY = "copy"
        const val EVENT_REVEAL = "reveal"
        const val EVENT_CREATE = "create"
        const val EVENT_UPDATE = "update"
        const val EVENT_DELETE = "delete"
        const val EVENT_EXPORT = "export"
        const val EVENT_IMPORT = "import"
    }
}
