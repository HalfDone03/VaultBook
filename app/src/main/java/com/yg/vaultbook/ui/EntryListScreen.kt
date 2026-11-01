package com.yg.vaultbook.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yg.vaultbook.core.util.Normalize
import com.yg.vaultbook.core.util.Pinyin
import com.yg.vaultbook.data.VaultGateway
import com.yg.vaultbook.data.entity.AuditLogEntity
import com.yg.vaultbook.domain.EntrySummary
import com.yg.vaultbook.domain.PlainCategory
import com.yg.vaultbook.widget.VaultWidgetProvider
import com.yg.vaultbook.widget.WidgetCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 条目列表。
 *
 * 搜索是**纯内存**的：字段全加密，SQL 层没法 `LIKE`。
 * 索引在 [items] 变化时预计算一次（把标题/账号/网址拼起来归一化），
 * 输入时只做 `contains` —— 1000 条量级下每次按键 < 5ms，感觉不到延迟。
 *
 * ── 视觉结构（v1.4）─────────────────────────────────────────
 * 上一版所有东西都是同一层的灰卡片，扫一眼分不清主次。现在分三层：
 *   背景（暖灰）→ 卡片（纯白 + 1dp 阴影）→ 强调（头像渐变块 + 胶囊按钮）
 * 每条记录左侧有一个按站点着色的方块，**颜色本身成了索引** ——
 * "那个绿色的淘宝"比"第三行那个"好找得多。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryListScreen(
    gateway: VaultGateway,
    onOpenEntry: (String) -> Unit,
    onCreateEntry: () -> Unit,
    onEditEntry: (String) -> Unit = {},
    pendingCount: Int = 0,
    onOpenPending: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    onOpenGenerator: () -> Unit = {},
    onOpenCategories: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    /**
     * 已经播过入场动画的条目。
     *
     * LazyColumn 滚动时会回收再重建 item，`remember` 会跟着重置 ——
     * 不记下来的话，往回滚一次就重播一次错落动画，看起来像列表在抽筋。
     * 这个集合只是"查过没有"，不驱动重组，所以用普通 mutableSet 就够。
     *
     * 由上层持有：进详情会把列表页整个销毁，"返回"回来时若这里跟着重建，
     * 一屏卡片会再从头播一遍入场动画，像刚加载完一样。
     */
    playedIds: MutableSet<String> = remember { mutableSetOf() },
    /** 列表滚动位置，同样由上层持有（见 [listState] 的说明）。 */
    listState: LazyListState = rememberLazyListState()
) {
    val repo = remember { gateway.repositoryOrNull() }

    if (repo == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("数据还没打开", color = MaterialTheme.colorScheme.error)
        }
        return
    }

    // Flow 必须 remember：`collectAsState` 以 Flow 实例为 key，
    // 若每次重组都调用 observeSummaries() 新建一个 Flow，
    // 会不停取消订阅再重新订阅 —— 表现为列表闪烁 + 数据库被反复查询。
    val entriesFlow = remember(repo) { repo.observeSummaries() }
    val items by entriesFlow.collectAsState(initial = emptyList())
    // 搜索词与筛选条要活过转屏和进程重建：
    // 正搜到一半转个屏，回来搜索框空了、列表跳回全部，是很难受的体验。
    var query by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }

    // 分类名要解密一次才能显示在筛选条上。
    //
    // 走 Room 的 Flow 而不是"条目数变了就重取"：改分类名不改条目数，
    // 那种写法下改完名回到列表，筛选条上还是旧名字，要杀进程才更新。
    val categories by remember(repo) { repo.observeCategories() }
        .collectAsState(initial = emptyList<PlainCategory>())

    // 当前筛选：全部 / 收藏 / 某个分类 / 未分类。
    // 记住的是筛选对象本身而不是下标 —— 分类可以增删，下标会漂。
    var filter by rememberSaveable(stateSaver = QuickFilterSaver) {
        mutableStateOf<QuickFilter>(QuickFilter.All)
    }

    // 搜索索引 + 各筛选的条目数：一次遍历算完，且不放在组合期。
    //
    // 原先这是两个 `remember(items) { ... }`：一个拼全量搜索串再做归一化 + 拼音
    // 首字母映射，另一个把整个列表分组统计一遍。两件事都在主线程的组合期跑，
    // 条目一多（导入备份几百条之后最明显）列表就会卡一下，搜索框的每次重组也会
    // 顺带重算计数。
    //
    // 现在合并成一次遍历丢到 Dispatchers.Default 上算，算完才触发重组。
    // 用 produceState 而不是 remember：items 变化时旧值继续显示（上一次的
    // 索引与计数仍然有效可用），不会先变空再填回来，避免筛选条数字闪一下。
    val derived by produceState(ListDerived.EMPTY, items) {
        value = if (items.isEmpty()) {
            ListDerived.EMPTY
        } else {
            withContext(Dispatchers.Default) { computeListDerived(items) }
        }
    }

    val filters = remember(categories, derived) { buildFilters(categories, derived.counts) }

    // 把最常用的前三条同步给桌面小组件。
    //
    // 为什么在这里写而不是在 Provider 里读库：BroadcastReceiver 里开 SQLCipher
    // 的失败是静默的，表现出来就是桌面一块空白，属于最难排查的问题。
    // 列表页本来就有排好序的 items（favorite → last_used → updated），
    // 前三条正好是"最常用三条"，顺手写进 SharedPreferences 即可。
    //
    // 第一次 items 还是空列表（Flow 未发射）时跳过，否则会先写一次空缓存、
    // 让桌面闪一下空态。
    var cachePrimed by remember { mutableStateOf(false) }
    // 单独取一份 context：下面 200 行开外的 `context` 声明在这个 LaunchedEffect
    // 之后，直接用会 Unresolved。
    val widgetContext = LocalContext.current
    LaunchedEffect(items) {
        if (!cachePrimed && items.isEmpty()) return@LaunchedEffect
        cachePrimed = true
        // 去抖：批量写库时会连续发射好几次（导入备份、批量移动分类、清空回收站），
        // 每次都全量重写 SharedPreferences 再广播一次，桌面小组件会被反复重建。
        // LaunchedEffect 的键一变就会取消上一个协程，所以 delay 天然实现"只跑最后一次"。
        delay(WIDGET_REFRESH_DEBOUNCE_MS)
        val rows = items.take(WidgetCache.MAX_ROWS).map { entry ->
            WidgetCache.Row(
                id = entry.id,
                title = entry.title,
                username = entry.username,
                initial = entry.title.trim().take(1).ifEmpty { "·" }
            )
        }
        WidgetCache.save(widgetContext, rows)
        VaultWidgetProvider.refresh(widgetContext)
    }

    // 先按筛选收窄，再在收窄结果里搜索 —— 顺序反过来的话，
    // "在登录分类里搜 taobao" 会先搜出全网再筛分类，白做一遍全量匹配。
    val filtered = remember(items, query, derived, filter) {
        // 取到局部变量：filter 是 `by remember` 的委托属性，
        // 直接在 when 分支里用它做智能转换会报 "delegated property"。
        val current = filter
        val scoped = when (current) {
            QuickFilter.All -> items
            QuickFilter.Favorite -> items.filter { it.favorite }
            QuickFilter.Uncategorized -> items.filter { it.categoryId == null }
            is QuickFilter.Category -> items.filter { it.categoryId == current.id }
        }
        val q = Normalize.forSearch(query)
        if (q.isEmpty()) scoped
        else scoped.filter { derived.searchIndex[it.id]?.contains(q) == true }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /**
     * 复制某条记录的密码到剪贴板（30 秒后自动清空）。
     *
     * 解密出来的 `CharArray` 用完立刻清零；交给剪贴板的 `String` 清不掉
     * （Android API 限制），靠 [copySecret] 的定时清除补偿。
     * 复制行为写审计，"谁在什么时候复制了哪条"是这类工具唯一有价值的日志。
     */
    fun copyPassword(id: String) {
        scope.launch {
            val plain = runCatching { repo.revealPassword(id) }.getOrNull() ?: return@launch
            try {
                copySecret(context, "密码", String(plain))
                runCatching { repo.audit(AuditLogEntity.EVENT_COPY, id, "password") }
                // 记一次使用：列表按 last_used_at 排序，复制过的条目会浮到前面
                runCatching { repo.markUsed(id) }
                snackbar.showSnackbar("密码已复制，30 秒后自动清空剪贴板")
            } finally {
                plain.fill('\u0000')
            }
        }
    }

    /**
     * 复制账号：比密码低一级但仍是高频动作 ——
     * 有些站点要先填账号再等它查一次，这次复制就省掉了手动输入。
     */
    fun copyUsername(id: String) {
        scope.launch {
            val user = runCatching { repo.loadDetail(id)?.summary?.username }.getOrNull()
            if (user.isNullOrBlank()) {
                snackbar.showSnackbar("这条没有账号")
                return@launch
            }
            copyPlainText(context, "账号", user)
            runCatching { repo.audit(AuditLogEntity.EVENT_COPY, id, "username") }
            runCatching { repo.markUsed(id) }
            snackbar.showSnackbar("账号已复制")
        }
    }

    fun trash(id: String) {
        scope.launch {
            runCatching { repo.trashEntry(id) }
            snackbar.showSnackbar("已移到回收站，30 天后自动清除")
        }
    }

    // 长按哪一条就把它记在这里，底部动作单读这个值
    var actionTarget by remember { mutableStateOf<EntrySummary?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ListHeader(
                total = items.size,
                pendingCount = pendingCount,
                onOpenPending = onOpenPending,
                onOpenTrash = onOpenTrash,
                onOpenGenerator = onOpenGenerator,
                onOpenCategories = onOpenCategories,
                onOpenSettings = onOpenSettings
            )
        },
        floatingActionButton = {
            // 自己画而不是用 FloatingActionButton：要给它渐变色和更大的阴影，
            // 而 M3 的 FAB 只吃单色。
            Box(Modifier.enterFadeSlide(delayMillis = 120, distanceDp = 28f)) {
                val fabSource = remember { MutableInteractionSource() }
                val fabShape = RoundedCornerShape(20.dp)
                Box(
                    Modifier
                        .size(60.dp)
                        // 阴影单独画在裁剪之外：放进 Surface 里的话，
                        // 圆角裁切会把阴影一起裁掉，剩下一条硬边。
                        .shadow(elevation = 12.dp, shape = fabShape, clip = false)
                        .clip(fabShape)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.80f)
                                )
                            )
                        )
                        .pressScale(fabSource, pressedScale = 0.92f)
                        .clickable(
                            interactionSource = fabSource,
                            indication = null,
                            onClick = onCreateEntry
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "新建",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        // 只在视线落点上做一层空气感：顶部主色极淡的光晕 + 右上角一点琥珀余晖。
        // 不铺满整屏 —— 整屏染色会把卡片之间的层次压平，反而更糊。
        val washColor = MaterialTheme.colorScheme.primary
        val glowColor = MaterialTheme.colorScheme.secondary

        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .drawBehind {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                washColor.copy(alpha = 0.10f),
                                washColor.copy(alpha = 0.03f),
                                Color.Transparent
                            ),
                            startY = 0f,
                            endY = 240.dp.toPx()
                        )
                    )
                    val radius = 420.dp.toPx()
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                glowColor.copy(alpha = 0.07f),
                                Color.Transparent
                            ),
                            center = Offset(size.width, 0f),
                            radius = radius
                        ),
                        radius = radius,
                        center = Offset(size.width, 0f)
                    )
                }
        ) {
            SearchField(
                query = query,
                onQueryChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )

            Spacer(Modifier.height(10.dp))

            // 16 个分类平铺放不下，横向滚一条。只显示"有条目的"分类 ——
            // 空分类点进去是一条空列表，纯属浪费一次点击。
            FilterBar(
                filters = filters,
                selected = filter,
                onSelect = { filter = it }
            )

            Spacer(Modifier.height(12.dp))

            if (filtered.isEmpty()) {
                EmptyState(
                    hasQuery = query.isNotBlank(),
                    hasFilter = filter != QuickFilter.All,
                    onCreateEntry = onCreateEntry,
                    onClearFilter = { filter = QuickFilter.All }
                )
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 2.dp, bottom = 112.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(11.dp)
                ) {
                    itemsIndexed(filtered, key = { _, entry -> entry.id }) { index, entry ->
                        // 首次出现的行才有入场动画；滚回来的行用 1ms 直接到位
                        val isNew = remember(entry.id) { playedIds.add(entry.id) }
                        // 回调按条目 id 记住：不然每次筛选/搜索引起重组，
                        // 这个 lambda 都是新对象，EntryRow 会跟着整片重组。
                        val onOpen = remember(entry.id) { { onOpenEntry(entry.id) } }
                        val onLongPress: () -> Unit = remember(entry.id) { { actionTarget = entry } }
                        val onCopy = remember(entry.id) { { copyPassword(entry.id) } }
                        EntryRow(
                            entry = entry,
                            onClick = onOpen,
                            onLongClick = onLongPress,
                            onCopyPassword = onCopy,
                            modifier = Modifier
                                // 增删/排序变化时让行自己滑动到位，而不是瞬间跳过去
                                .animateItem()
                                .enterFadeSlide(
                                    delayMillis = if (isNew) staggerDelay(index) else 0,
                                    distanceDp = 18f,
                                    durationMillis = if (isNew) Motion.Slow else 1
                                )
                        )
                    }
                }
            }
        }
    }

    // 长按动作单：不点进详情也能拿账号、复制密码、改、删。
    // 用底部弹层而不是下拉菜单，是因为下拉菜单会跟着行滚动漂移，
    // 而且只能显示在点的那一行旁边 —— 一行在屏幕最底部时会被挤到看不见。
    val target = actionTarget
    if (target != null) {
        ModalBottomSheet(
            onDismissRequest = { actionTarget = null },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    EntryAvatar(
                        title = target.title,
                        seed = target.hostNorm ?: target.packageNorm ?: target.title,
                        size = 40.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = target.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val sub = target.username ?: target.hostNorm ?: ""
                        if (sub.isNotEmpty()) {
                            Text(
                                text = sub,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                ActionRow("复制密码", enabled = target.hasPassword) {
                    actionTarget = null
                    copyPassword(target.id)
                }
                ActionRow("复制账号", enabled = !target.username.isNullOrBlank()) {
                    actionTarget = null
                    copyUsername(target.id)
                }
                ActionRow("查看详情") {
                    actionTarget = null
                    onOpenEntry(target.id)
                }
                ActionRow("编辑") {
                    actionTarget = null
                    onEditEntry(target.id)
                }
                ActionRow("移到回收站", danger = true) {
                    actionTarget = null
                    trash(target.id)
                }
            }
        }
    }
}

/**
 * 动作单的一行。
 *
 * 单独抽出来是因为五行里只有一行是危险的，「删除」要单独上色 ——
 * 混在一个 forEach 里迟早会漏掉这个区分。
 */
@Composable
private fun ActionRow(
    label: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 15.dp)
    )
}

/**
 * 顶栏。
 *
 * 不用 `TopAppBar`：它会把标题限制在一行、字号锁死在 titleLarge，
 * 而这里想要"大标题 + 小计数"的杂志式排版，还要给搜索框留出呼吸空间。
 * 自己拼一个 Row 反而更简单，面积也更好控制。
 */
@Composable
private fun ListHeader(
    total: Int,
    pendingCount: Int,
    onOpenPending: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenGenerator: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "VaultBook",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(5.dp))
                // 计数从一行灰字改成小胶囊：它是"状态"，不是副标题。
                // 灰小字挂在 34sp 标题下面，怎么调间距都像半句话被截断了。
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = if (total == 0) "还没有记录" else "共 $total 条",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(Modifier.width(4.dp))

            // 三个入口统一成 40dp 圆形轻底：原来是三个裸图标，
            // 点在图标之间的空白处没有任何反馈，视觉上也不成组。
            HeaderAction(
                icon = Icons.Filled.Notifications,
                description = if (pendingCount > 0) "待确认 $pendingCount 条" else "待确认",
                badge = pendingCount,
                onClick = onOpenPending
            )

            Box {
                HeaderAction(
                    icon = Icons.Filled.MoreVert,
                    description = "更多",
                    onClick = { menuOpen = true }
                )
                androidx.compose.material3.DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    listOf(
                        "生成密码" to onOpenGenerator,
                        "分类管理" to onOpenCategories,
                        "回收站" to onOpenTrash
                    ).forEach { (label, action) ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                menuOpen = false
                                action()
                            }
                        )
                    }
                }
            }

            HeaderAction(
                icon = Icons.Filled.Settings,
                description = "设置",
                onClick = onOpenSettings
            )
        }
    }
}

/**
 * 顶栏的圆形图标按钮。
 *
 * 抽出来的原因：顶栏三个入口必须长得一样宽、一样高、一样的按下反馈，
 * 三处各写一遍迟早会漂移（之前就是三个光秃秃的 IconButton，间距全靠默认值）。
 */
@Composable
private fun HeaderAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    badge: Int = 0
) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.60f))
            .pressScale(source, pressedScale = 0.92f)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val tint = MaterialTheme.colorScheme.onSurfaceVariant
        if (badge > 0) {
            BadgedBox(badge = { Badge { Text("$badge") } }) {
                Icon(
                    icon,
                    contentDescription = description,
                    tint = tint,
                    modifier = Modifier.size(20.dp)
                )
            }
        } else {
            Icon(
                icon,
                contentDescription = description,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * 搜索框。
 *
 * 做成胶囊、去边框、填充纯白：它是"在卡片层"的元素，不是表单字段。
 * 有内容时右侧出现清空按钮 —— 手机上删掉一长串搜索词很烦。
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val focused = remember { mutableStateOf(false) }
    val accent = if (focused.value) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = {
            Text(
                "搜索标题 / 账号 / 网址 / 首字母",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
            )
        },
        leadingIcon = {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(19.dp)
            )
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "清空",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        } else null,
        singleLine = true,
        // 圆角矩形而不是胶囊：胶囊让搜索框看起来像一个"按钮"，
        // 而它其实是这页唯一的输入框，方形一点更像输入框。
        shape = RoundedCornerShape(15.dp),
        textStyle = MaterialTheme.typography.bodyMedium,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
        ),
        modifier = modifier
            // 不写死高度：M3 的填充式输入框自带 56dp 最小高度，
            // 外面再压到 50dp 会把内部装饰挤到裁切，输入大字号时更明显。
            // 圆角 + 描边已经足够把它和页面其他块区分开。
            // 描边走 Modifier 而不是 TextField 的 indicator：
            // indicator 只在底部画一条线，这里要的是整圈。
            .clip(RoundedCornerShape(15.dp))
            .border(
                width = if (focused.value) 1.5.dp else 1.dp,
                color = if (focused.value) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = RoundedCornerShape(15.dp)
            )
            .onFocusChanged { focused.value = it.isFocused }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    entry: EntrySummary,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCopyPassword: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardSource = remember { MutableInteractionSource() }
    val copySource = remember { MutableInteractionSource() }

    // 色条和复制按钮共用同一个强调色。一行里出现两种强调色会打架，
    // 而每条各自有颜色，整屏才有"花花绿绿但有序"的观感，不是一片灰白。
    val accentSeed = entry.hostNorm ?: entry.packageNorm ?: entry.title
    val accent = remember(accentSeed) { accentOf(accentSeed) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .pressScale(cardSource, pressedScale = 0.985f)
            .combinedClickable(
                interactionSource = cardSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        // 2dp + 一圈极淡的边：只靠阴影，深色模式下卡片会完全融进背景
        // （黑底黑卡，阴影是不可见的），描边是让边界在任何主题下都成立。
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        )
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧一道 4dp 色条，颜色跟头像同源。
            // 卡片本身已经够素了，这一笔是给"扫列表"用的锚点：
            // 眼睛先认出颜色，再看字，比一行行读标题快。
            Box(
                Modifier
                    .width(4.dp)
                    .height(30.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.verticalGradient(
                            listOf(accent, accent.copy(alpha = 0.30f))
                        )
                    )
            )

            Spacer(Modifier.width(8.dp))

            // 颜色按站点定：同一个网站的多条记录同色，改标题也不会变
            EntryAvatar(
                title = entry.title,
                seed = accentSeed,
                size = 44.dp
            )

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (entry.favorite) {
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = "★",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                // 账号可以不填（很多人只记"平台 + 密码"），
                // 这时退到网址、再退到密码本身的状态，副标题不能空着 ——
                // 空一行会让卡片看着像没加载完。
                val subtitle = entry.username
                    ?: entry.hostNorm
                    ?: entry.url
                    ?: if (entry.hasPassword) "只有密码" else ""
                if (subtitle.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (entry.hasPassword) {
                            StrengthDot(entry.strengthScore, hasPassword = true)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // 一键复制：日常最高频的动作就是"打开某个站，复制密码去登录"。
            // 放进详情页要多点两下，所以直接给到列表行上。
            // 没密码的条目不显示（点了也没东西可复制）。
            if (entry.hasPassword) {
                Spacer(Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(11.dp),
                    // 按钮底色跟着这条记录的颜色走：整屏看过去每条都有自己的一点色，
                    // 但饱和度压得很低，不会盖过标题。
                    color = accent.copy(alpha = 0.13f),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.34f))
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(11.dp))
                            .pressScale(copySource, pressedScale = 0.93f)
                            .clickable(
                                interactionSource = copySource,
                                indication = null,
                                onClick = onCopyPassword
                            )
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        // 不引 material-icons-extended：为了一枚图标把整个图标库
                        // 打进 dex（R8 会裁，但构建和体积都要付代价），
                        // 靠底色 + 描边 + 圆角把这块做成"按钮"更划算。
                        Text(
                            text = "复制",
                            style = MaterialTheme.typography.labelMedium,
                            color = accent
                        )
                    }
                }
            }
        }
    }
}

/**
 * 密码强度指示点。
 *
 * 无密码时返回空布局而不是灰点 —— 灰点在深色模式下几乎看不见，
 * 又容易被读成"很弱"，不如干脆不画。
 */
@Composable
private fun StrengthDot(score: Int, hasPassword: Boolean) {
    if (!hasPassword) return

    val color = when {
        score <= 1 -> MaterialTheme.colorScheme.error
        score == 2 -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(color)
    )
}

/**
 * 空状态。
 *
 * 三种空法要说不同的话：
 *   - 有搜索词：你搜的东西不在
 *   - 有筛选但没搜索：这个分类里还没有记录
 *   - 都没有：这里还什么都没有
 *
 * 最后一种给一个新建按钮 —— 视线本来就落在这里，再让用户去够右下角的
 * FAB 是多余的一步。有筛选时给一个"看全部"，因为用户很可能忘了自己筛过。
 */
@Composable
private fun EmptyState(
    hasQuery: Boolean,
    hasFilter: Boolean,
    onCreateEntry: () -> Unit,
    onClearFilter: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.enterFadeSlide(delayMillis = 80, distanceDp = 20f)
        ) {
            EmptyArtwork()

            Spacer(Modifier.height(20.dp))

            Text(
                text = when {
                    hasQuery -> "没有匹配的条目"
                    hasFilter -> "这个分类还是空的"
                    else -> "还没有记录"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = when {
                    hasQuery && hasFilter -> "换个关键词，或看看全部记录"
                    hasQuery -> "换个关键词，或检查有没有打错字"
                    hasFilter -> "新建时选这个分类，或把已有记录改过来"
                    else -> "点下面的按钮，或把账号密码粘贴进来自动识别"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            if (hasFilter) {
                Spacer(Modifier.height(20.dp))
                TextButton(onClick = onClearFilter) { Text("看全部记录") }
            } else if (!hasQuery) {
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onCreateEntry,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("新建第一条", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// 快速筛选条
// ─────────────────────────────────────────────────────────────

/**
 * 筛选范围。
 *
 * 用 `data object` / `data class` 而不是枚举，因为 [Category] 要带 id；
 * 枚举办不到这件事，而"用 id + 一个 currentCategoryId 字段"会让
 * "当前是不是收藏筛选"变成两个变量的组合判断。
 */
private sealed interface QuickFilter {
    fun key(): String

    data object All : QuickFilter {
        override fun key(): String = "all"
    }

    data object Favorite : QuickFilter {
        override fun key(): String = "fav"
    }

    data object Uncategorized : QuickFilter {
        override fun key(): String = "none"
    }

    data class Category(val id: String) : QuickFilter {
        override fun key(): String = "cat:$id"
    }
}

/**
 * 把筛选状态存成 `"all"` / `"fav"` / `"none"` / `"cat:<id>"` 这样的短字符串。
 *
 * 存 key 而不是存下标：分类可以增删，下标会漂到别的分类上去。
 * 恢复时认不出的 key（分类被删了）退回「全部」，不会留下一个空列表。
 */
private val QuickFilterSaver: Saver<QuickFilter, Any> = listSaver<QuickFilter, String>(
    save = { listOf(it.key()) },
    restore = { v ->
        val key = v.firstOrNull().orEmpty()
        when {
            key == "fav" -> QuickFilter.Favorite
            key == "none" -> QuickFilter.Uncategorized
            key.startsWith("cat:") && key.length > 4 -> QuickFilter.Category(key.substring(4))
            else -> QuickFilter.All
        }
    }
)

private data class QuickCounts(
    val all: Int,
    val favorite: Int,
    /** 键为 `categoryId`，未分类的条目落在 `null` 键上。 */
    val byCategory: Map<String?, Int>
) {
    companion object {
        val EMPTY = QuickCounts(all = 0, favorite = 0, byCategory = emptyMap())
    }
}

/**
 * 列表页的派生数据：搜索索引 + 筛选计数。
 *
 * 打包成一个对象而不是两个状态，是为了让它们永远来自**同一次** items 快照 ——
 * 分开算的话会在重组中途出现"索引是新的、计数是旧的"这种半更新状态。
 */
private data class ListDerived(
    val searchIndex: Map<String, String>,
    val counts: QuickCounts
) {
    companion object {
        val EMPTY = ListDerived(emptyMap(), QuickCounts.EMPTY)
    }
}

/**
 * 一次遍历产出搜索索引与各筛选的条目数。
 *
 * 在 `Dispatchers.Default` 上跑，只读 [items] 快照，不碰 Compose 状态。
 */
private fun computeListDerived(items: List<EntrySummary>): ListDerived {
    val index = HashMap<String, String>(items.size * 2)
    val byCategory = HashMap<String?, Int>()
    var favorite = 0
    for (entry in items) {
        index[entry.id] = Pinyin.searchKey(entry.searchText())
        if (entry.favorite) favorite++
        byCategory[entry.categoryId] = (byCategory[entry.categoryId] ?: 0) + 1
    }
    return ListDerived(
        searchIndex = index,
        counts = QuickCounts(all = items.size, favorite = favorite, byCategory = byCategory)
    )
}

/** 小组件缓存刷新的去抖窗口：比一次批量写库的间隔长，短于用户能感知"桌面没更新"的阈值。 */
private const val WIDGET_REFRESH_DEBOUNCE_MS = 400L

private data class QuickFilterModel(
    val filter: QuickFilter,
    val label: String,
    val count: Int,
    val starred: Boolean = false
)

/**
 * 组装筛选条要显示哪些胶囊。
 *
 * 两条取舍：
 *   1. **只显示有内容的分类**。16 个分类全列出来会有三分之二是空的，
 *      点进去看到"这个分类还是空的"纯属浪费一次点击。
 *   2. **"未分类"只在真有记录时才出现**。用户一个分类都没建时，
 *      "全部"和"未分类"是同一批数据，显示两个一模一样的筛选没有意义。
 */
private fun buildFilters(
    categories: List<PlainCategory>,
    counts: QuickCounts
): List<QuickFilterModel> {
    val out = ArrayList<QuickFilterModel>(categories.size + 3)
    out += QuickFilterModel(QuickFilter.All, "全部", counts.all)

    if (counts.favorite > 0) {
        out += QuickFilterModel(QuickFilter.Favorite, "收藏", counts.favorite, starred = true)
    }

    categories.forEach { category ->
        val n = counts.byCategory[category.id] ?: 0
        if (n > 0) {
            out += QuickFilterModel(QuickFilter.Category(category.id), category.name, n)
        }
    }

    val loose = counts.byCategory[null] ?: 0
    if (loose > 0 && categories.isNotEmpty()) {
        out += QuickFilterModel(QuickFilter.Uncategorized, "未分类", loose)
    }

    return out
}

/**
 * 横向筛选条。
 *
 * 为什么不是 `TabRow`：分类数量是用户自己的（现在上限 16 个 + 收藏 + 未分类），
 * TabRow 会把标签挤成等宽，每个只剩两个字的空间。横滚胶囊能保持自然宽度，
 * 而且"右边还有东西"这件事靠露出的半个胶囊就能传达，不用额外提示。
 */
@Composable
private fun FilterBar(
    filters: List<QuickFilterModel>,
    selected: QuickFilter,
    onSelect: (QuickFilter) -> Unit
) {
    // 只有"全部"一项时整条不占位置，省 40dp 高度给列表
    if (filters.size <= 1) return

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(filters, key = { it.filter.key() }) { model ->
            FilterPill(
                model = model,
                active = model.filter == selected,
                onClick = { onSelect(model.filter) }
            )
        }
    }
}

@Composable
private fun FilterPill(
    model: QuickFilterModel,
    active: Boolean,
    onClick: () -> Unit
) {
    val source = remember { MutableInteractionSource() }
    val countColor = if (active) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        onClick = onClick,
        // 圆角矩形取代胶囊：和搜索框、卡片同一套圆角语言，
        // 整页看起来才是"一个设计"而不是三个组件拼的。
        shape = RoundedCornerShape(11.dp),
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = countColor,
        // 未选中的胶囊描一道极淡的边：纯白胶囊落在暖灰背景上，
        // 边界不够明确，看起来像浮着的文字
        border = if (active) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        // 选中的那个轻轻浮起来，视线一眼能落在当前筛选上
        shadowElevation = if (active) 5.dp else 0.dp,
        modifier = Modifier.pressScale(source, pressedScale = 0.95f)
    ) {
        Row(
            Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (model.starred) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = model.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Spacer(Modifier.width(5.dp))
            // 数字压到半透明：它是辅助信息，不该跟分类名抢注意力
            Text(
                text = model.count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = countColor.copy(alpha = 0.62f),
                maxLines = 1
            )
        }
    }
}
