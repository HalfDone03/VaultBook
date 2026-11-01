package com.yg.vaultbook.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 备份提醒。
 *
 * 只记一个时间戳，不存备份内容、不存密码。存在独立 prefs 里，
 * 因为这个值不属于保险库本身 —— 连保险库都没打开时也可能要读它。
 *
 * 阈值放 30 天：换手机、摔机、误删应用都会丢数据，提醒太勤会变成噪音，
 * 一个月一次刚好是「想起来还来得及」的频率。条目少于 5 条先不烦人。
 */
object BackupReminder {
    private const val PREFS = "backup_state"
    private const val KEY_LAST = "last_backup_at"

    const val REMIND_AFTER_DAYS = 30

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun record(context: Context, at: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_LAST, at).apply()
    }

    fun lastAt(context: Context): Long? =
        prefs(context).getLong(KEY_LAST, 0L).takeIf { it > 0L }

    /** 多少天前备份的；从没备份过返回 null。 */
    fun daysSince(context: Context, now: Long = System.currentTimeMillis()): Int? =
        lastAt(context)?.let { ((now - it) / 86_400_000L).toInt() }

    fun shouldRemind(
        context: Context,
        entryCount: Int,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (entryCount < 5) return false
        val days = daysSince(context, now) ?: return true
        return days >= REMIND_AFTER_DAYS
    }

    /** 给设置页做副标题的一句话。 */
    fun describe(context: Context, now: Long = System.currentTimeMillis()): String {
        val days = daysSince(context, now) ?: return "还没备份过"
        return when {
            days <= 0 -> "今天备份过"
            days == 1 -> "昨天备份过"
            else -> "$days 天前备份过"
        }
    }
}