package com.yg.vaultbook.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 桌面小组件的数据缓存。
 *
 * ── 为什么不让 widget 自己去开库 ─────────────────────────────
 * widget 运行在 `AppWidgetProvider` 里，那是 BroadcastReceiver 的主线程，
 * `onUpdate` 只有约 10 秒预算。要读库得：
 *
 *   1. 从 [com.yg.vaultbook.core.simple.SimpleKeyStore] 取口令
 *   2. `VaultDatabase.open` 打开 SQLCipher（首次要解页，几十到几百毫秒）
 *   3. 在后台线程查（Room 拒绝主线程访问）
 *   4. 关闭 + 清零口令
 *
 * 每一步都有失败可能，而失败的表现是"桌面上一块空白，没有任何提示"——
 * 最难排查的那种。
 *
 * 改成 **App 主进程在数据变化时把前几条写进这里**，widget 只读一个
 * SharedPreferences 文件：同步、毫秒级、不碰数据库、不会因为钥匙缺失而空白。
 *
 * ── 安全边界（明确写在这里，因为它确实是一份额外副本）──────
 * 缓存里是**明文标题与账号**，不含密码。这与 `entries` 表里的存储等级一致
 * （本项目已选明文方案，见 `PlainFieldCrypto`），所以没有额外降低安全性。
 * 但不能放密码 —— 密码一旦进了 prefs，就在应用沙箱里多了第二份拷贝，
 * 而 widget 根本用不到它（复制动作走 deep link 回主进程完成）。
 */
object WidgetCache {

    private const val PREFS = "widget_cache"
    private const val KEY_ROWS = "rows"
    private const val KEY_UPDATED_AT = "updated_at"

    /**
     * widget 上最多显示几条。
     *
     * 与 `widget_vault.xml` 的行数**必须一致**：布局里写死了三行
     * （RemoteViews 不能动态加行），多缓存一条也没有地方放。
     */
    const val MAX_ROWS = 3

    data class Row(
        val id: String,
        val title: String,
        val username: String?,
        val initial: String
    )

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 写入缓存。只保留前 [MAX_ROWS] 条。
     *
     * `commit()` 而不是 `apply()`：写完之后会立刻发广播要求 widget 重绘，
     * 而广播与重绘可能在另一个进程完成。用 apply 的话，落盘是异步的，
     * 对方有可能读到旧值 —— 表现为"新建了一条，桌面还是老样子"。
     * 一次几十条记录的 JSON，commit 的同步 IO 代价可以忽略。
     */
    @SuppressLint("ApplySharedPref")
    fun save(context: Context, rows: List<Row>) {
        val array = JSONArray()
        rows.take(MAX_ROWS).forEach { row ->
            array.put(
                JSONObject().apply {
                    put("id", row.id)
                    put("title", row.title)
                    put("username", row.username ?: JSONObject.NULL)
                    put("initial", row.initial)
                }
            )
        }
        prefs(context).edit()
            .putString(KEY_ROWS, array.toString())
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .commit()
    }

    /**
     * 读缓存。任何解析异常都退化成空列表 ——
     * widget 显示"还没数据"远好过整个桌面组件崩成一块灰。
     */
    fun load(context: Context): List<Row> {
        val raw = prefs(context).getString(KEY_ROWS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optString("id").takeIf { it.isNotEmpty() } ?: continue
                    add(
                        Row(
                            id = id,
                            title = item.optString("title").ifEmpty { "(无标题)" },
                            username = item.optString("username")
                                .takeIf { it.isNotEmpty() && it != "null" },
                            initial = item.optString("initial").takeIf { it.isNotEmpty() } ?: "·"
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun updatedAt(context: Context): Long = prefs(context).getLong(KEY_UPDATED_AT, 0L)

    @SuppressLint("ApplySharedPref")
    fun clear(context: Context) {
        prefs(context).edit().clear().commit()
    }
}
