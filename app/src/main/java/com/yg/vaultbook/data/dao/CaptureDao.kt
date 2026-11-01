package com.yg.vaultbook.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.yg.vaultbook.data.entity.DismissRuleEntity
import com.yg.vaultbook.data.entity.PendingCaptureEntity
import com.yg.vaultbook.data.entity.SiteMappingEntity
import kotlinx.coroutines.flow.Flow

/**
 * 待确认捕获。
 *
 * 查询里**不带** `expires_at > :now` 条件：Flow 是一次订阅长期推送的，
 * 把 now 写进 SQL 会让订阅在时间流逝后返回过期数据。
 * 过期过滤放在 `VaultRepository` 里用注入的时钟做，测试也好控制。
 */
@Dao
interface PendingCaptureDao {

    @Query("SELECT * FROM pending_captures WHERE state = 'pending' ORDER BY created_at DESC")
    fun observePending(): Flow<List<PendingCaptureEntity>>

    @Query("SELECT * FROM pending_captures WHERE state = 'pending' ORDER BY created_at DESC")
    suspend fun loadPending(): List<PendingCaptureEntity>

    @Query("SELECT * FROM pending_captures WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): PendingCaptureEntity?

    /** 去重用：同一站点同一账号 5 分钟内不重复提示 */
    @Query(
        "SELECT * FROM pending_captures WHERE host_norm = :host AND state = 'pending' " +
            "ORDER BY created_at DESC LIMIT 1"
    )
    suspend fun latestPendingForHost(host: String): PendingCaptureEntity?

    /**
     * 去重用（无域名的场景）。
     *
     * 原生 App 的登录页拿不到域名，`host_norm` 为 null，按 host 去重会完全失效 ——
     * 无障碍通道每次扫到同一页都会新增一条。所以包名也要有一条同样规则的去重查询。
     */
    @Query(
        "SELECT * FROM pending_captures WHERE package_norm = :pkg AND host_norm IS NULL " +
            "AND state = 'pending' ORDER BY created_at DESC LIMIT 1"
    )
    suspend fun latestPendingForPackage(pkg: String): PendingCaptureEntity?

    @Query("SELECT COUNT(*) FROM pending_captures WHERE state = 'pending'")
    suspend fun countPending(): Int

    @Upsert
    suspend fun upsert(row: PendingCaptureEntity)

    @Query("UPDATE pending_captures SET state = :state WHERE id = :id")
    suspend fun setState(id: String, state: String)

    @Query("DELETE FROM pending_captures WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM pending_captures WHERE expires_at < :now")
    suspend fun purgeExpired(now: Long): Int

    /** 已被接受的捕获没有必要留着密文 */
    @Query("DELETE FROM pending_captures WHERE state != 'pending'")
    suspend fun purgeResolved(): Int
}

/** 站点 → 条目学习映射。 */
@Dao
interface SiteMappingDao {

    @Query("SELECT * FROM site_mappings WHERE host_norm = :host ORDER BY weight DESC, last_used_at DESC")
    suspend fun byHost(host: String): List<SiteMappingEntity>

    @Query("SELECT * FROM site_mappings WHERE package_norm = :pkg ORDER BY weight DESC, last_used_at DESC")
    suspend fun byPackage(pkg: String): List<SiteMappingEntity>

    @Query("SELECT * FROM site_mappings WHERE entry_id = :entryId")
    suspend fun byEntry(entryId: String): List<SiteMappingEntity>

    @Upsert
    suspend fun upsert(row: SiteMappingEntity)

    /** 用户主动选了一次：权重 +3，与排序策略对应 */
    @Query("UPDATE site_mappings SET weight = weight + :delta, last_used_at = :now WHERE id = :id")
    suspend fun bump(id: String, delta: Int, now: Long)

    @Query("DELETE FROM site_mappings WHERE entry_id = :entryId")
    suspend fun deleteForEntry(entryId: String)
}

/** "别再问我"静默规则，按 host 计时。 */
@Dao
interface DismissRuleDao {

    @Query("SELECT until_at FROM dismiss_rules WHERE host_norm = :host LIMIT 1")
    suspend fun untilAt(host: String): Long?

    @Upsert
    suspend fun upsert(rule: DismissRuleEntity)

    @Query("DELETE FROM dismiss_rules WHERE host_norm = :host")
    suspend fun clear(host: String)

    @Query("DELETE FROM dismiss_rules WHERE until_at < :now")
    suspend fun purgeExpired(now: Long): Int
}
