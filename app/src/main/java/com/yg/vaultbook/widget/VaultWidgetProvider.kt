package com.yg.vaultbook.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.yg.vaultbook.MainActivity
import com.yg.vaultbook.R

/**
 * 桌面小组件：最常用的三条 + 新建入口。
 *
 * ── 它解决的那一步 ─────────────────────────────────────────
 * 没有小组件时的最短路径：
 *   解锁手机 → 找图标 → 打开VaultBook → 找到那一条 → 长按 / 进详情 → 复制
 *
 * 有小组件之后：
 *   解锁手机 → 点桌面上的那一行 → （应用打开 + 自动复制）
 *
 * 少三次点击，而且**不用回忆它叫什么** —— 名字就在桌面上写着。
 *
 * ── 数据从哪来 ─────────────────────────────────────────────
 * 不读数据库，只读 [WidgetCache]（由主进程在数据变化时写入）。
 * 理由写在那个类的注释里：broadcast 里开 SQLCipher 的失败是静默的，
 * 表现为桌面一块空白，属于最难排查的一类问题。
 *
 * ── 点击为什么不是直接复制 ─────────────────────────────────
 * Android 10 起，**后台进程写剪贴板会被系统静默丢弃**。BroadcastReceiver
 * 正是后台进程，所以"widget 里直接复制"在没有前台窗口时根本不会生效。
 * 因此点击走 deep link 拉起应用，由前台完成复制 —— 应用会顺带打开详情页，
 * 让用户看见复制了什么。
 */
class VaultWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        if (appWidgetIds.isEmpty()) return
        // 一份 RemoteViews 可以给多个实例用（内容相同），不必每条重建
        val views = buildViews(context)
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            refresh(context)
            return
        }
        super.onReceive(context, intent)
    }

    companion object {

        /** 由应用内部广播触发刷新。widget 自己不会定时醒。 */
        const val ACTION_REFRESH = "com.yg.vaultbook.widget.REFRESH"

        /** 深链前缀，与 [MainActivity] 的 intent-filter 对应。 */
        private const val SCHEME = "vaultbook"

        /**
         * 主动刷新所有 widget 实例。
         *
         * 桌面上一个都没有时立刻返回 —— 别为了不存在的视图去拼 RemoteViews。
         */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(
                ComponentName(context, VaultWidgetProvider::class.java)
            )
            if (ids == null || ids.isEmpty()) return
            val views = buildViews(context)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_vault)
            val rows = WidgetCache.load(context)

            // 头部「＋新建」
            views.setOnClickPendingIntent(R.id.widget_new, activityIntent(context, "new", 1001))

            val slotIds = intArrayOf(
                R.id.widget_row1, R.id.widget_row2, R.id.widget_row3
            )

            if (rows.isEmpty()) {
                slotIds.forEach { views.setViewVisibility(it, View.GONE) }
                views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
                // 空态整块也能点：文案写的是"点这里打开"
                views.setOnClickPendingIntent(R.id.widget_empty, activityIntent(context, "new", 1002))
                return views
            }

            views.setViewVisibility(R.id.widget_empty, View.GONE)

            slots.forEachIndexed { index, slot ->
                val row = rows.getOrNull(index)
                if (row == null) {
                    views.setViewVisibility(slot.container, View.GONE)
                    return@forEachIndexed
                }

                views.setViewVisibility(slot.container, View.VISIBLE)
                views.setTextViewText(slot.initial, row.initial)
                views.setTextViewText(slot.title, row.title)
                views.setTextViewText(slot.sub, row.username ?: "")

                // requestCode 用 index 而不是 id.hashCode()：
                // hashCode 可能为负、可能撞车，而 index 一定在 0..2。
                // 但 index 重复用会让"同一位置换了条目"复用旧 PendingIntent，
                // 所以 data uri 里带了 id —— FLAG_UPDATE_CURRENT 会按 data 区分。
                views.setOnClickPendingIntent(
                    slot.container,
                    copyIntent(context, row.id, 2000 + index)
                )
            }

            return views
        }

        /** 三行各自的 id 集合。逐行展开的代价就是这里要写三遍。 */
        private val slots: List<RowSlot>
            get() = listOf(
                RowSlot(R.id.widget_row1, R.id.widget_row1_initial, R.id.widget_row1_title, R.id.widget_row1_sub),
                RowSlot(R.id.widget_row2, R.id.widget_row2_initial, R.id.widget_row2_title, R.id.widget_row2_sub),
                RowSlot(R.id.widget_row3, R.id.widget_row3_initial, R.id.widget_row3_title, R.id.widget_row3_sub)
            )

        private data class RowSlot(
            val container: Int,
            val initial: Int,
            val title: Int,
            val sub: Int
        )

        /**
         * 打开应用的某个深层目标的 PendingIntent。
         *
         * 全部用 `FLAG_IMMUTABLE`：Android 12 起可变 PendingIntent 必须显式声明，
         * 而这里没有任何需要系统回填的字段。
         * `FLAG_UPDATE_CURRENT` 让同一 requestCode 换了 data 之后能覆盖旧的。
         */
        private fun activityIntent(context: Context, host: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("$SCHEME://$host")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun copyIntent(context: Context, entryId: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("$SCHEME://copy/$entryId")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
