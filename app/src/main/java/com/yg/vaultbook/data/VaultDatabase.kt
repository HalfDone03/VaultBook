package com.yg.vaultbook.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.yg.vaultbook.data.dao.AuditLogDao
import com.yg.vaultbook.data.dao.CategoryDao
import com.yg.vaultbook.data.dao.CustomFieldDao
import com.yg.vaultbook.data.dao.DismissRuleDao
import com.yg.vaultbook.data.dao.EntryDao
import com.yg.vaultbook.data.dao.EntryHistoryDao
import com.yg.vaultbook.data.dao.PendingCaptureDao
import com.yg.vaultbook.data.dao.SiteMappingDao
import com.yg.vaultbook.data.entity.AuditLogEntity
import com.yg.vaultbook.data.entity.CategoryEntity
import com.yg.vaultbook.data.entity.CustomFieldEntity
import com.yg.vaultbook.data.entity.DismissRuleEntity
import com.yg.vaultbook.data.entity.EntryEntity
import com.yg.vaultbook.data.entity.EntryHistoryEntity
import com.yg.vaultbook.data.entity.PendingCaptureEntity
import com.yg.vaultbook.data.entity.SiteMappingEntity
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 加密数据库。
 *
 * 整个文件由 SQLCipher 用 AES-256-CBC + HMAC 加密，口令是 `dbKey`（32 字节随机值），
 * 而 `dbKey` 本身被 DEK 包裹存在 [VaultMetaStore] 里。三层拆开的意义：
 * 拿到 `.db` 文件拿不到数据；拿到 meta 文件也拿不到数据；两者都拿到、但不知道主密码，
 * 还是拿不到数据。
 *
 * ── 为什么没有 fallbackToDestructiveMigration ──────────────────
 * 那会让"改了一版表结构"直接变成"用户数据全没了"。
 * 表结构变更必须写 Migration。宁可启动时报错，也不能静默清库。
 */
@Database(
    entities = [
        CategoryEntity::class,
        EntryEntity::class,
        EntryHistoryEntity::class,
        CustomFieldEntity::class,
        SiteMappingEntity::class,
        PendingCaptureEntity::class,
        AuditLogEntity::class,
        DismissRuleEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class VaultDatabase : RoomDatabase() {

    abstract fun entryDao(): EntryDao
    abstract fun categoryDao(): CategoryDao
    abstract fun entryHistoryDao(): EntryHistoryDao
    abstract fun customFieldDao(): CustomFieldDao
    abstract fun pendingCaptureDao(): PendingCaptureDao
    abstract fun siteMappingDao(): SiteMappingDao
    abstract fun dismissRuleDao(): DismissRuleDao
    abstract fun auditLogDao(): AuditLogDao

    companion object {

        const val FILE_NAME = "vault.db"

        private const val SQLCIPHER_LIB = "sqlcipher"

        private val nativeLoaded = AtomicBoolean(false)

        /**
         * 加载 SQLCipher 的 native 库。必须在 [open] 之前调用一次。
         *
         * 显式加载而不是依赖静态初始化，是为了让"这台设备缺少对应 ABI 的 .so"
         * 这种情况在启动时就暴露成一条清晰的错误，而不是等到用户输完密码才崩。
         */
        fun ensureNativeLoaded() {
            if (nativeLoaded.get()) return
            synchronized(nativeLoaded) {
                if (nativeLoaded.get()) return
                System.loadLibrary(SQLCIPHER_LIB)
                nativeLoaded.set(true)
            }
        }

        /**
         * 用解出来的 `dbKey` 打开库。
         *
         * @param dbKey 32 字节 SQLCipher 口令，来自 `VaultKeys.dbKey`
         */
        fun open(context: Context, dbKey: ByteArray): VaultDatabase {
            require(dbKey.size == 32) { "dbKey 必须为 32 字节，实际 ${dbKey.size}" }
            ensureNativeLoaded()

            return Room.databaseBuilder(
                context.applicationContext,
                VaultDatabase::class.java,
                FILE_NAME
            )
                .openHelperFactory(SupportOpenHelperFactory(dbKey.copyOf()))
                .build()
        }
    }
}
