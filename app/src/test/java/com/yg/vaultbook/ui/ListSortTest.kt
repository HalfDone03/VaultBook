package com.yg.vaultbook.ui

import com.yg.vaultbook.domain.EntrySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ListSortMode] / [sortSummaries] / [buildSearchKeys] 的行为测试。
 *
 * 这三件事的共同点是「纯函数决定用户看到什么顺序、什么能搜到」，
 * 而它们在真机上的表现几乎无法自动化验收（没有仪器测试），
 * 所以只能靠这里把契约钉死：
 *
 *   - 排序必须是**稳定**的：相同键的元素保持原相对顺序，否则每次重组
 *     列表会换个样子（用户会以为自己在误触）。
 *   - 中文「按名称」必须走拼音首字母，不能按码点。
 *   - 未分类、未知分类必须落到末尾，不能冒充第一个。
 *   - 备注进索引后要能搜到，但缺备注时不能崩、也不能把别的字段挤掉。
 */
class ListSortTest {

    // ── 工具 ────────────────────────────────────────────────

    /** 只关心 id / title / createdAt / categoryId，其余字段填不影响排序的常量。 */
    private fun entry(
        id: String,
        title: String,
        createdAt: Long = 0L,
        categoryId: String? = null,
        username: String? = null,
        url: String? = null,
        hostNorm: String? = null
    ) = EntrySummary(
        id = id,
        categoryId = categoryId,
        title = title,
        username = username,
        url = url,
        hostNorm = hostNorm,
        packageNorm = null,
        favorite = false,
        strengthScore = 0,
        pwdUpdatedAt = null,
        createdAt = createdAt,
        updatedAt = createdAt,
        lastUsedAt = null,
        useCount = 0,
        hasPassword = true,
        hasTotp = false
    )

    private fun ids(items: List<EntrySummary>) = items.map { it.id }

    // ── 模式解析 ────────────────────────────────────────────

    @Test
    fun `存的 key 能还原成同一个模式`() {
        ListSortMode.values().forEach { mode ->
            assertEquals(mode, ListSortMode.of(mode.key))
        }
    }

    @Test
    fun `认不出的 key 退回最近使用`() {
        assertEquals(ListSortMode.RECENT, ListSortMode.of(null))
        assertEquals(ListSortMode.RECENT, ListSortMode.of(""))
        assertEquals(ListSortMode.RECENT, ListSortMode.of("按长度"))
        // 大小写敏感：写盘的是小写 key，手改成大写不该蒙对
        assertEquals(ListSortMode.RECENT, ListSortMode.of("TITLE"))
    }

    // ── 最近使用 ────────────────────────────────────────────

    @Test
    fun `最近使用原样返回不重排`() {
        // DAO 已经按三级 ORDER BY 排好，内存里再排一次只会打乱它
        val given = listOf(
            entry("c", "淘宝"),
            entry("a", "支付宝"),
            entry("b", "Apple")
        )
        assertEquals(ids(given), ids(sortSummaries(given, ListSortMode.RECENT)))
    }

    // ── 按名称 ──────────────────────────────────────────────

    @Test
    fun `按名称走拼音首字母而不是码点`() {
        // 按 Unicode 码点，「淘宝」在「支付宝」之后、但都在 ASCII 之后；
        // 按拼音首字母应是 a(apple) < t(tb) < z(zfb)
        val given = listOf(
            entry("z", "支付宝"),
            entry("a", "Apple"),
            entry("t", "淘宝")
        )
        assertEquals(
            listOf("a", "t", "z"),
            ids(sortSummaries(given, ListSortMode.TITLE))
        )
    }

    @Test
    fun `按名称时中文同首字母按后续首字母分组`() {
        // 「京东」jd / 「坚果」jg / 「记忆」jy —— 首字母 j 相同，看第二个字母
        val given = listOf(
            entry("jy", "记忆"),
            entry("jd", "京东"),
            entry("jg", "坚果")
        )
        assertEquals(
            listOf("jd", "jg", "jy"),
            ids(sortSummaries(given, ListSortMode.TITLE))
        )
    }

    @Test
    fun `按名称是稳定排序`() {
        // 三条同名：排序后必须保持进来时的顺序，不能随机
        val given = listOf(
            entry("first", "淘宝"),
            entry("second", "淘宝"),
            entry("third", "淘宝")
        )
        assertEquals(
            listOf("first", "second", "third"),
            ids(sortSummaries(given, ListSortMode.TITLE))
        )
    }

    // ── 最近新建 ────────────────────────────────────────────

    @Test
    fun `最近新建按创建时间倒序`() {
        val given = listOf(
            entry("mid", "中间", createdAt = 200),
            entry("new", "最新", createdAt = 300),
            entry("old", "最旧", createdAt = 100)
        )
        assertEquals(
            listOf("new", "mid", "old"),
            ids(sortSummaries(given, ListSortMode.CREATED))
        )
    }

    // ── 按分类 ──────────────────────────────────────────────

    @Test
    fun `按分类先按分类位次再按名称`() {
        val rank = mapOf<String?, Int>("work" to 0, "life" to 1)
        val given = listOf(
            entry("life-b", "淘宝", categoryId = "life"),
            entry("work-b", "淘宝", categoryId = "work"),
            entry("work-a", "Apple", categoryId = "work"),
            entry("life-a", "Apple", categoryId = "life")
        )
        assertEquals(
            listOf("work-a", "work-b", "life-a", "life-b"),
            ids(sortSummaries(given, ListSortMode.CATEGORY, rank))
        )
    }

    @Test
    fun `按分类时未分类和未知分类都排最后`() {
        val rank = mapOf<String?, Int>("work" to 0)
        val given = listOf(
            entry("none", "无分类", categoryId = null),
            entry("work", "工作项", categoryId = "work"),
            // 刚建的分类还没进观察流，位次表里查不到
            entry("ghost", "幽灵分类", categoryId = "brand-new")
        )
        val sorted = sortSummaries(given, ListSortMode.CATEGORY, rank)
        assertEquals("work", sorted.first().id)
        // 后两条都落到末尾，彼此之间按名称排（无分类 w / 幽灵分类 y）
        assertEquals(listOf("work"), sorted.take(1).map { it.id })
        assertEquals(setOf("none", "ghost"), sorted.drop(1).map { it.id }.toSet())
    }

    @Test
    fun `按分类时缺位次表也不崩`() {
        val given = listOf(
            entry("a", "淘宝", categoryId = "x"),
            entry("b", "Apple", categoryId = "y")
        )
        // 不传 categoryRank：全部落到末尾，退化成分组内按名称
        assertEquals(
            listOf("b", "a"),
            ids(sortSummaries(given, ListSortMode.CATEGORY))
        )
    }

    // ── 搜索索引 ────────────────────────────────────────────

    @Test
    fun `搜索索引覆盖备注`() {
        val given = listOf(entry("a", "淘宝"))
        val index = buildSearchKeys(given, mapOf("a" to "绑定的是妈妈的手机号"))
        val key = index.getValue("a")
        assertTrue("备注原文要能搜到", key.contains("妈妈"))
        // 首字母串也要带上备注的字，「妈妈」→ m
        assertTrue("备注的拼音首字母也要能搜到", key.contains("m"))
    }

    @Test
    fun `没有备注时索引等于原有字段`() {
        val given = listOf(entry("a", "淘宝", username = "me@example.com"))
        val withEmpty = buildSearchKeys(given, emptyMap())
        val withBlank = buildSearchKeys(given, mapOf("a" to "   "))
        val withNull = buildSearchKeys(given, mapOf("a" to ""))
        // 空、纯空白、缺失都必须退化成同一个结果，不能多出分隔符
        assertEquals(withEmpty, withBlank)
        assertEquals(withEmpty, withNull)
        assertTrue(withEmpty.getValue("a").contains("me@example.com"))
    }

    @Test
    fun `索引不会因为缺备注少掉任何一条`() {
        val given = listOf(entry("a", "淘宝"), entry("b", "京东"))
        val index = buildSearchKeys(given, mapOf("a" to "有备注"))
        assertEquals(setOf("a", "b"), index.keys)
    }

    @Test
    fun `备注接在分隔符后面而不是直接粘上去`() {
        // 分隔符的作用范围要说清楚：它隔开的是**原文段**（搜 `cdef` 不会命中
        // 标题 `abc` + 备注 `def`）。但拼音首字母串本身就是把所有字的声母
        // 顺次连起来，跨字段连成一串是这个设计自带的限制 —— 所以这里只钉
        // 原文段必须带分隔符，不假装首字母段也隔开了。
        val given = listOf(entry("x", "abc"))
        val key = buildSearchKeys(given, mapOf("x" to "def")).getValue("x")
        assertTrue("原文段要保留分隔符", key.contains("abc\u0001def"))
    }
}
