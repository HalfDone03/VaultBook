package com.yg.vaultbook.capture

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yg.vaultbook.MainActivity

/**
 * 捕获通知 —— 告诉用户「刚才那次登录我已经记下来了，去确认一下」。
 *
 * ── 为什么必须有它 ────────────────────────────────────────────
 * 捕获是**完全无感**的：用户在浏览器里点登录，本应用没露面。如果什么都不提示，
 * 用户永远不知道待确认列表里已经躺着东西了，功能等于不存在。
 *
 * ── 三个实现上的坑 ────────────────────────────────────────────
 * 1. **小图标用系统资源** `android.R.drawable.ic_lock_lock`。
 *    launcher 图标是自适应矢量图（`res/mipmap-anydpi-v26/`），带彩色背景，
 *    **不能**直接拿来当通知小图标 —— 通知栏小图标必须是单色 alpha 遮罩，
 *    给彩色图标会被渲染成一团白块。系统图标在所有 API 26+ 设备上都存在。
 * 2. **必须检查通知权限**。API 33+ 没有 `POST_NOTIFICATIONS` 时 `notify()` 抛
 *    `SecurityException`，而这里是在无障碍服务里被调用的 —— 抛出去会让服务被系统判异常。
 * 3. **`IMPORTANCE_LOW`**：要出现，但不要响。用户正在输密码，此时"叮"一声
 *    是纯粹的干扰，而且会暴露"这个手机在记东西"。
 */
class CaptureNotifier(private val context: Context) {

    /** 建通知渠道。API 26+ 必须，重复调用是幂等的。 */
    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "捕获待确认",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "在别处登录后，这里会提示有新捕获的账号密码待确认"
            setShowBadge(true)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    /**
     * 提示有待确认的捕获。
     *
     * @param count 当前待确认总数；小于等于 0 时什么也不做
     */
    fun notifyPending(count: Int) {
        if (count <= 0) return

        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val open = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_PENDING, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("已捕获 $count 条待确认")
            .setContentText("点开确认后才会存进保险库")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // 个别 ROM 即使 areNotificationsEnabled() 为真也会在 notify 时抛，
        // 而调用方是无障碍服务，异常外泄会被系统判为服务异常。
        // 显式 checkSelfPermission 是 lint 的硬要求（@RequiresPermission 检查），
        // 也是这里真正的第一道闸：33+ 没授权时 notify 必然抛 SecurityException。
        if (!hasPostNotificationPermission(context)) return
        @SuppressLint("MissingPermission")
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun hasPostNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** 待确认清空后，把通知也撤掉。 */
    fun cancel() {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    companion object {
        const val CHANNEL_ID = "capture_pending"

        /** 通知点击时带这个 extra，MainActivity 会直接打开待确认页。 */
        const val EXTRA_OPEN_PENDING = "com.yg.vaultbook.extra.OPEN_PENDING"

        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_CODE = 1001
    }
}
