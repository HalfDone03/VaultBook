package com.yg.vaultbook.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.widget.Toast
import java.security.MessageDigest

/**
 * 剪贴板工具。
 *
 * 为什么不放在 `EntryEditScreen` 里：详情页、生成器页都要复制密码，
 * 而"复制后 30 秒自动清空"这条规则必须**只有一份实现** ——
 * 两处各写一份，迟早有一处忘了清，那一处就成了密码长期驻留剪贴板的漏洞。
 */
const val SECRET_CLIPBOARD_TTL_MS: Long = 30_000L

/** 落盘的清理约定。只存内容的 SHA-256，不存明文。 */
private const val PREFS_NAME = "clip_guard"
private const val KEY_HASH = "pending_hash"
private const val KEY_UNTIL = "pending_until"

private const val HEX = "0123456789abcdef"

private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

/** 复制普通文本（账号、网址）。不需要定时清除。 */
fun copyPlainText(context: Context, label: String, value: String) {
    writeClip(context, label, value)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        // API 33 起系统自己会弹"已复制"浮层，再 toast 就是重复提示
        toast(context, "已复制$label")
    }
}

/**
 * 复制敏感文本（密码），并在 [SECRET_CLIPBOARD_TTL_MS] 后清空剪贴板。
 *
 * 两件事：
 *   1. 标记 `EXTRA_IS_SENSITIVE` —— Android 13+ 系统不会在长按输入框的预览里
 *      显示这串内容，也不会进剪贴板历史。不标的话，密码是明文挂在预览上的。
 *   2. 把到期时间落盘，配合 [purgeSecretClip] 在进程重启后补清。
 *
 * 读剪贴板在 Android 12+ 有限制：只有前台应用或默认输入法能读。
 * 读不到就**放弃这一轮**（而不是盲清），失败路径偏向"不动用户数据"；
 * 记录留着，下次回到前台再试一次。
 */
fun copySecret(context: Context, label: String, value: String) {
    val appContext = context.applicationContext
    if (!writeClip(appContext, label, value, sensitive = true)) return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        toast(appContext, "已复制$label，30 秒后自动清除")
    }
    rememberPending(appContext, value)
    // 多等 1 秒：正好卡在边界上时 `now < until` 可能还成立，那一轮就白跑了
    mainHandler.postDelayed({ purgeSecretClip(appContext) }, SECRET_CLIPBOARD_TTL_MS + 1_000)
}

/**
 * 到期就清。由三处调用：复制后的定时器、App 冷启动、Activity 回到前台。
 *
 * ── 为什么不能只有 Handler ────────────────────────────────────
 * `Handler.postDelayed` 挂在进程的主线程 Looper 上，进程被系统回收后回调
 * 直接消失 —— 剪贴板里的密码会一直留着。"30 秒自动清除"恰好在最需要它的
 * 时候（用户切走、App 被杀）失效，这是原先的实现最要命的地方。
 * 到期时间落盘之后，进程再起来还能补一次清理。
 *
 * 清除前比对 SHA-256：只有内容**仍然是当初写进去的那串**才清，
 * 用户中途复制了别的东西就不能动。不存明文，只存摘要。
 */
fun purgeSecretClip(context: Context) {
    val appContext = context.applicationContext
    val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val hash = prefs.getString(KEY_HASH, null) ?: return
    val until = prefs.getLong(KEY_UNTIL, 0L)
    if (System.currentTimeMillis() < until) return

    val current = currentClipText(appContext)
    if (current == null) {
        // 后台读不到剪贴板（Android 12+ 限制）。记录留着，回到前台再试。
        return
    }
    if (sha256(current) == hash) {
        writeClip(appContext, "", "")
    }
    prefs.edit().remove(KEY_HASH).remove(KEY_UNTIL).apply()
}

private fun rememberPending(context: Context, value: String) {
    runCatching {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HASH, sha256(value))
            .putLong(KEY_UNTIL, System.currentTimeMillis() + SECRET_CLIPBOARD_TTL_MS)
            .apply()
    }
}

private fun clipboard(context: Context): ClipboardManager? =
    context.getSystemService(ClipboardManager::class.java)

private fun writeClip(
    context: Context,
    label: String,
    value: String,
    sensitive: Boolean = false
): Boolean {
    val cm = clipboard(context) ?: return false
    return runCatching {
        val clip = ClipData.newPlainText(label, value)
        if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+ 才认这个标记；低版本忽略即可，不影响复制本身
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        cm.setPrimaryClip(clip)
        true
    }.getOrDefault(false)
}

private fun currentClipText(context: Context): String? {
    val cm = clipboard(context) ?: return null
    return runCatching {
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        clip.getItemAt(0).text?.toString()
    }.getOrNull()
}

private fun sha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) {
        val v = b.toInt() and 0xFF
        sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
    }
    return sb.toString()
}

private fun toast(context: Context, text: String) {
    runCatching { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
}
