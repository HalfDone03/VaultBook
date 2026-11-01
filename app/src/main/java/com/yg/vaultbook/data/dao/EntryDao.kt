package com.yg.vaultbook.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.yg.vaultbook.data.entity.CategoryEntity
import com.yg.vaultbook.data.entity.CustomFieldEntity
import com.yg.vaultbook.data.entity.EntryEntity
import com.yg.vaultbook.data.entity.EntryHistoryEntity
import com.yg.vaultbook.data.entity.EntryNotesRow
import com.yg.vaultbook.data.entity.EntrySummaryRow
import kotlinx.coroutines.flow.Flow

/**
 * 条目读写。
 *
 * ⚠️ 这里一律用 `@Upsert` 而不是 `@Insert(onConflict = REPLACE)`：
 * REPLACE 的实现是 **DELETE 旧行 + INSERT 新行**，而 `entry_history` 和
 * `custom_fields` 都带 `ON DELETE CASCADE` —— 用 REPLACE 更新一条记录，
 * 会把它自己的密码历史和自定义字段一起删掉。这是 SQLite 上最隐蔽的数据丢失来源之一。
 * `@Upsert` 走 `INSERT ... ON CONFLICT DO UPDATE`，只改列，不删行。
 *
 * 所有查询都返回**密文实体**；解密在 `VaultRepository` 层完成，
 * DAO 层拿不到 `FieldCipher`，从结构上保证不会在数据层误用明文。
 */
@Dao
interface EntryDao {

    /**
     * 列表主查询。
     *
     * 排序分三级，按"用户的期待"排：
     * 1. `favorite DESC` —— 星标过的永远在最前，这是用户唯一能手动钉住的东西；
     * 2. `last_used_at DESC` —— 刚查过的排前面。速查本的典型用法是
     *    "十分钟内同一条看两三次"，让它自己浮上来比手动搜索快；
     * 3. `updated_at DESC` —— 都没用过时，新建/改过的在前。
     *
     * `COALESCE` 是因为 `last_used_at` 可空（从没用过），
     * 直接 DESC 排序时 NULL 在 SQLite 里会被当成最小值排到最后 —— 效果一样，
     * 但显式写 0 让意图清楚，也不会因为 SQLite 版本差异改变行为。
     */
    @Query(
        "SELECT * FROM entries WHERE deleted_at IS NULL " +
            "ORDER BY favorite DESC, COALESCE(last_used_at, 0) DESC, updated_at DESC"
    )
    fun observeActive(): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE deleted_at IS NULL")
    suspend fun loadActive(): List<EntryEntity>

    /**
     * 列表页投影查询：排序列与 [observeActive] 完全一致，只是不搬 `notes_ct` /
     * `password_ct` / `totp_ct` 的密文本体，后两者改在 SQL 里折成布尔。
     *
     * `IS NOT NULL` 在 SQLite 里对 BLOB 是常数时间的空值判断，不会读取内容。
     */
    @Query(
        "SELECT id, category_id, title_ct, username_ct, url_ct, host_norm, package_norm, " +
            "favorite, strength_score, pwd_updated_at, created_at, updated_at, last_used_at, " +
            "use_count, deleted_at, " +
            "(password_ct IS NOT NULL) AS has_password, (totp_ct IS NOT NULL) AS has_totp " +
            "FROM entries WHERE deleted_at IS NULL " +
            "ORDER BY favorite DESC, COALESCE(last_used_at, 0) DESC, updated_at DESC"
    )
    fun observeSummaryRows(): Flow<List<EntrySummaryRow>>

    @Query(
        "SELECT id, category_id, title_ct, username_ct, url_ct, host_norm, package_norm, " +
            "favorite, strength_score, pwd_updated_at, created_at, updated_at, last_used_at, " +
            "use_count, deleted_at, " +
            "(password_ct IS NOT NULL) AS has_password, (totp_ct IS NOT NULL) AS has_totp " +
            "FROM entries WHERE deleted_at IS NULL"
    )
    suspend fun loadSummaryRows(): List<EntrySummaryRow>

    /**
     * 搜索索引专用：只取 id 与备注密文，不含回收站里的。
     *
     * 与 [observeSummaryRows] 分开，是为了让列表刷新不为备注付 I/O；
     * 见 `EntryNotesRow` 的说明。
     */
    @Query("SELECT id, notes_ct FROM entries WHERE deleted_at IS NULL")
    suspend fun loadNotesCipher(): List<EntryNotesRow>

    @Query("SELECT * FROM entries WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC")
    suspend fun loadTrashed(): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): EntryEntity?

    @Query(
        "SELECT * FROM entries WHERE host_norm = :host AND deleted_at IS NULL " +
            "ORDER BY COALESCE(last_used_at, 0) DESC, updated_at DESC"
    )
    suspend fun findActiveByHost(host: String): List<EntryEntity>

    @Query(
        "SELECT * FROM entries WHERE package_norm = :pkg AND deleted_at IS NULL " +
            "ORDER BY COALESCE(last_used_at, 0) DESC, updated_at DESC"
    )
    suspend fun findActiveByPackage(pkg: String): List<EntryEntity>

    @Query("SELECT COUNT(*) FROM entries")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM entries WHERE deleted_at IS NULL")
    suspend fun countActive(): Int

    /**
     * 导出备份用：**含回收站里的**，不过滤 `deleted_at`。
     *
     * 备份的语义是"把我库里的东西完整搬到另一个地方"，
     * 少带走回收站那部分，用户换机后会发现"我上周删的那条怎么不见了"。
     */
    @Query("SELECT * FROM entries ORDER BY updated_at DESC")
    suspend fun loadEverything(): List<EntryEntity>

    /** 导入前一次性把已有 id 取出来，避免逐条 findById（1000 条就是 1000 次往返） */
    @Query("SELECT id FROM entries")
    suspend fun allIds(): List<String>

    @Upsert
    suspend fun upsert(entry: EntryEntity)

    @Query("UPDATE entries SET deleted_at = :now, updated_at = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE entries SET deleted_at = NULL, updated_at = :now WHERE id = :id")
    suspend fun restore(id: String, now: Long)

    /** 物理删除。只在回收站到期或用户点"彻底删除"时调用。 */
    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun purge(id: String)

    @Query("DELETE FROM entries WHERE deleted_at IS NOT NULL AND deleted_at < :before")
    suspend fun purgeTrashedBefore(before: Long): Int

    @Query("UPDATE entries SET last_used_at = :now, use_count = use_count + 1 WHERE id = :id")
    suspend fun markUsed(id: String, now: Long)

    /**
     * 删除分类前把这个分类下的条目摘出来（`category_id` 置 NULL）。
     *
     * 外键上写的是 `onDelete = SET_NULL`，理论上 SQLite 会自己做 ——
     * 但那条外键只在 `PRAGMA foreign_keys=ON` 时生效，而 SQLCipher 的
     * `SupportOpenHelperFactory` 不保证打开该 pragma。所以显式更新一次，
     * 不把「条目会不会丢分类」这件事押在 pragma 上。
     */
    @Query("UPDATE entries SET category_id = NULL WHERE category_id = :categoryId")
    suspend fun clearCategory(categoryId: String): Int

    /** 某个分类下还有多少条（含回收站里的）。分类管理页用来提示。 */
    @Query("SELECT COUNT(*) FROM entries WHERE category_id = :categoryId")
    suspend fun countByCategory(categoryId: String): Int
}

/** 分类。数量小（个位数），全部一次读进内存。 */
@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories ORDER BY sort ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sort ASC")
    suspend fun loadAll(): List<CategoryEntity>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    /** 当前最大 sort 值。新建分类排到末尾用；空表返回 null。 */
    @Query("SELECT MAX(sort) FROM categories")
    suspend fun maxSort(): Int?

    @Upsert
    suspend fun upsertAll(rows: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun delete(id: String)
}

/** 密码历史。只在密码变化时写入一条。 */
@Dao
interface EntryHistoryDao {

    @Insert
    suspend fun insert(row: EntryHistoryEntity)

    @Query("SELECT * FROM entry_history WHERE entry_id = :entryId ORDER BY changed_at DESC")
    suspend fun all(entryId: String): List<EntryHistoryEntity>

    @Query("SELECT COUNT(*) FROM entry_history WHERE entry_id = :entryId")
    suspend fun count(entryId: String): Int

    /**
     * 只保留最近 [keep] 条。
     * 子查询先选出要留的 id，再删其余 —— 注意不能在同一个 DELETE 里直接对同表做 LIMIT。
     */
    @Query(
        "DELETE FROM entry_history WHERE entry_id = :entryId AND id NOT IN (" +
            "SELECT id FROM entry_history WHERE entry_id = :entryId " +
            "ORDER BY changed_at DESC LIMIT :keep)"
    )
    suspend fun trim(entryId: String, keep: Int)

    @Query("DELETE FROM entry_history WHERE entry_id = :entryId")
    suspend fun deleteForEntry(entryId: String)
}

/** 自定义字段。编辑页整体覆盖式保存，所以有 deleteForEntry。 */
@Dao
interface CustomFieldDao {

    @Query("SELECT * FROM custom_fields WHERE entry_id = :entryId ORDER BY sort ASC")
    suspend fun forEntry(entryId: String): List<CustomFieldEntity>

    /**
     * 导出备份用：一次取出全部自定义字段。
     *
     * 不给每条条目单独调 [forEntry] 是因为那是 N+1：1000 条就是 1000 次
     * 跨 JNI 的查询。一次取回再按 entry_id 分组，代价只有一次扫描。
     */
    @Query("SELECT * FROM custom_fields ORDER BY entry_id ASC, sort ASC")
    suspend fun loadAll(): List<CustomFieldEntity>

    @Upsert
    suspend fun upsertAll(rows: List<CustomFieldEntity>)

    @Query("DELETE FROM custom_fields WHERE entry_id = :entryId")
    suspend fun deleteForEntry(entryId: String)
}
