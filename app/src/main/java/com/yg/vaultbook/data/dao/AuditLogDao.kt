package com.yg.vaultbook.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.yg.vaultbook.data.entity.AuditLogEntity

/**
 * 审计日志。
 *
 * 只提供"插入"和"读最近 N 条"，没有 UPDATE —— 日志被改写就失去意义。
 */
@Dao
interface AuditLogDao {

    @Insert
    suspend fun insert(entry: AuditLogEntity)

    @Query("SELECT * FROM audit_log ORDER BY created_at DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AuditLogEntity>

    @Query("SELECT COUNT(*) FROM audit_log")
    suspend fun count(): Int

    /**
     * 只保留最近 [keep] 条。
     * 注意这里是按 `created_at, id` 双列排序：同一毫秒写入多条时
     * 只按时间排会得到不确定的顺序，裁剪结果也就不可复现。
     */
    @Query(
        "DELETE FROM audit_log WHERE id NOT IN (" +
            "SELECT id FROM audit_log ORDER BY created_at DESC, id DESC LIMIT :keep)"
    )
    suspend fun trim(keep: Int): Int

    @Query("DELETE FROM audit_log")
    suspend fun clear()
}
