package com.yg.vaultbook.ui

import android.content.Context
import com.yg.vaultbook.core.util.Normalize
import com.yg.vaultbook.core.util.Pinyin
import com.yg.vaultbook.domain.EntrySummary

/**
 * 列表排序方式。
 *
 * ── 为什么要有它 ──────────────────────────────────────────────
 * 原先排序写死在 `EntryDao` 的三级 `ORDER BY`（星标 → 最近用过 → 改过时间）。
 * 那套顺序服务的是"速查"这个主场景，但它有一个绕不过去的副作用：
 * **只要没搜到，列表就是一堆按使用频率排的名次**，找"那个我忘了名字、
 * 只记得大概位置"的条目反而更难 —— 用户对自己库的心理模型常常是
 * 按名字、按分类，而不是按"我上次什么时候用过"。
 *
 * 所以排序做成用户可选，默认仍是原来那套（[RECENT]），不改既有手感。
 *
 * ── 为什么排序在内存里做而不是换 SQL ──────────────────────────
 * 中文名的"按名称"要求按拼音首字母排，SQLite 的 `ORDER BY` 只会按
 * Unicode 码点排，出来的顺序（"阿"在"波"后面还是前面）对中文用户毫无意义。
 * 拼音表在 Kotlin 里（[Pinyin]），拿不到 SQL 里，只能拉回来在内存中排。
 * 条目量级是几百到几千，排一次几毫秒，代价可以接受。
 */
enum class ListSortMode(val key: String, val label: String) {
    /** 默认：星标 → 最近用过 → 改过时间。由 DAO 排好，这里原样返回。 */
    RECENT("recent", "最近使用"),

    /** 按名称（中文走拼音首字母）。 */
    TITLE("title", "按名称"),

    /** 按新建时间，新的在前。 */
    CREATED("created", "最近新建"),

    /** 按分类分组，组内仍按名称；未分类排最后。 */
    CATEGORY("category", "按分类");

    companion object {
        /**
         * 从存盘的 key 还原。
         *
         * 认不出的 key（旧版本写的、被手改过的）一律退回 [RECENT]：
         * 排序是纯显示偏好，退回到默认值不会丢东西，没必要为它报错。
         */
        fun of(key: String?): ListSortMode =
            values().firstOrNull { it.key == key } ?: RECENT
    }
}

/**
 * 排序偏好的落盘位置。
 *
 * 单独一个 `SharedPreferences` 文件而不是塞进 `simple_vault`：
 * 那边存的是库口令，语义上属于"加密层"，不该混进 UI 偏好。
 */
object ListSortPrefs {

    private const val FILE = "list_prefs"
    private const val KEY_SORT = "sort_mode"

    fun load(context: Context): ListSortMode =
        ListSortMode.of(prefs(context).getString(KEY_SORT, null))

    fun save(context: Context, mode: ListSortMode) {
        prefs(context).edit().putString(KEY_SORT, mode.key).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

/**
 * 按 [mode] 排序。
 *
 * 全部用 Kotlin 的稳定排序（`sortedWith` / `sortedBy`），相同键的元素保持
 * 数据库给的相对顺序 —— 否则"按分类"排完，同一分类内部会变成随机序，
 * 每次重组都可能换个样子。
 *
 * @param categoryRank 分类排序位次（键为 `categoryId`，`null` 是未分类）。
 *   不在表里的分类（刚建、还没进观察流）落到末尾，不会排到最前面冒充"第一个"。
 */
fun sortSummaries(
    items: List<EntrySummary>,
    mode: ListSortMode,
    categoryRank: Map<String?, Int> = emptyMap()
): List<EntrySummary> = when (mode) {
    ListSortMode.RECENT -> items
    ListSortMode.TITLE -> items.sortedBy { titleKey(it) }
    ListSortMode.CREATED -> items.sortedByDescending { it.createdAt }
    ListSortMode.CATEGORY -> items.sortedWith(
        compareBy({ categoryRank[it.categoryId] ?: Int.MAX_VALUE }, { titleKey(it) })
    )
}

/**
 * 排序用的名称串：**只取拼音首字母串**（「淘宝」→`tb`、「支付宝」→`zfb`）。
 *
 * 这里不能用 `Pinyin.searchKey` —— 它返回的是「归一化原文 + 空格 + 首字母」，
 * 拿它当排序键，字符串比较会先比原文那几个汉字，也就是又退回按 Unicode
 * 码点排（「京」U+4EAC 排在「记」U+8BB0 前，跟拼音顺序毫无关系）。
 * 「按名称」对中文用户的意义就是按拼音，所以键必须只由首字母构成。
 *
 * 首字母为空（纯符号、生僻字、繁体）时退回归一化原文：这类标题本来就没有
 * 拼音顺序可言，让它们按字面排至少是确定的。
 */
private fun titleKey(entry: EntrySummary): String {
    val normalized = Normalize.forSearch(entry.title)
    val initials = Pinyin.initials(normalized)
    return initials.ifEmpty { normalized }
}

/**
 * 拼搜索索引：条目自身的可见字段 + 备注明文。
 *
 * ── 为什么备注要单独传进来 ────────────────────────────────────
 * 列表页的投影查询故意不搬 `notes_ct`（备注可能有几百字，几千条时
 * 每刷新一次列表都多搬一份大字段，纯属浪费）。所以备注由调用方按需
 * 单独取一次密文、解密后传进来。取不到（还没加载完、解密失败）就是空表，
 * 此时索引退化成"只搜标题/账号/网址"，不会因为备注挂了导致搜不了。
 */
fun buildSearchKeys(
    items: List<EntrySummary>,
    noteTexts: Map<String, String>
): Map<String, String> {
    val index = HashMap<String, String>(items.size * 2)
    for (entry in items) {
        val base = entry.searchText()
        val note = noteTexts[entry.id]
        index[entry.id] = if (note.isNullOrBlank()) {
            Pinyin.searchKey(base)
        } else {
            // `\u0001` 是 searchText() 内部用的分隔符，备注接在后面同一套
            Pinyin.searchKey(base + '\u0001' + note)
        }
    }
    return index
}
