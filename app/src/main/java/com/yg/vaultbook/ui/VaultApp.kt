package com.yg.vaultbook.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.yg.vaultbook.App
import com.yg.vaultbook.core.simple.DbKeyMissingException
import kotlinx.coroutines.launch

/**
 * 顶层导航。
 *
 * ── 启动流程 ─────────────────────────────────────────────────
 * 打开 App 的第一件事是 [com.yg.vaultbook.data.VaultGateway.openSimple]：
 * 没有密码、没有解锁页，直接连库、种内置分类、清过期回收站，然后进列表。
 *
 * 只有三种状态：加载中 / 就绪 / 失败。[Failed] 只在两种情况下出现 ——
 * 库文件还在但口令文件丢了，或者库文件本身损坏。这两种都不能静默新建，
 * 否则用户以为"数据还在，只是没显示"。
 *
 * ── 为什么不用 Navigation-Compose ────────────────────────────
 * 屏幕虽然有九个，但彼此的跳转关系是一棵很浅的树（列表为中心，其余都是叶子），
 * 一个 sealed 状态机就够，少一个依赖、少一处回栈 bug。
 *
 * @param openPendingSignal 从通知点进来时递增，直接落到待确认页。
 * @param sharedText 从别的应用「分享」进来的文本（`ACTION_SEND` / `text/plain`）。
 *   非空时会打开新建页并尝试识别填入，消费后由 [onSharedTextConsumed] 清空。
 */
private sealed interface Screen {
    data object List : Screen
    data object Pending : Screen
    data object Trash : Screen
    data object Generator : Screen
    data object Categories : Screen
    data object Settings : Screen
    data object Backup : Screen
    data object Audit : Screen
    data object Security : Screen

    /** [reloadKey] 从编辑页返回时递增 —— `entryId` 没变，只能靠它触发重载。 */
    data class Detail(
        val entryId: String,
        val reloadKey: Long = 0L,
        /** 进来就自动复制密码。桌面小组件点某一行时用。 */
        val autoCopy: Boolean = false,
        /** 每次从小组件点进来都不同，让自动复制能重复触发。 */
        val autoCopyToken: Int = 0
    ) : Screen

    /** [fromDetail] 决定保存后回详情页还是回列表（新建条目没有详情可回）。 */
    data class Edit(val entryId: String?, val fromDetail: Boolean = false) : Screen
}

/**
 * 让"现在在哪一页"活过 Activity 重建（转屏、切深色、进程被回收后恢复）。
 *
 * 只存稳定的标识（页面名 + `entryId`），不存页面内部的临时状态 ——
 * 恢复出来的那一页会自己重新读库。
 *
 * `autoCopy` 故意不存：进程都重建了还替用户复制一次密码，属于"我让你复制了吗"，
 * 恢复成普通详情页更安全。
 */
private val ScreenSaver: Saver<Screen, Any> = listSaver<Screen, String>(
    save = { s ->
        when (s) {
            Screen.List -> listOf("list")
            Screen.Pending -> listOf("pending")
            Screen.Trash -> listOf("trash")
            Screen.Generator -> listOf("generator")
            Screen.Categories -> listOf("categories")
            Screen.Settings -> listOf("settings")
            Screen.Backup -> listOf("backup")
            Screen.Audit -> listOf("audit")
            Screen.Security -> listOf("security")
            is Screen.Detail -> listOf("detail", s.entryId, s.reloadKey.toString())
            is Screen.Edit -> listOf("edit", s.entryId.orEmpty(), if (s.fromDetail) "1" else "0")
        }
    },
    restore = { v ->
        when (v.firstOrNull()) {
            "pending" -> Screen.Pending
            "trash" -> Screen.Trash
            "generator" -> Screen.Generator
            "categories" -> Screen.Categories
            "settings" -> Screen.Settings
            "backup" -> Screen.Backup
            "audit" -> Screen.Audit
        "security" -> Screen.Security
            "detail" -> {
                val id = v.getOrNull(1)
                if (id.isNullOrBlank()) Screen.List
                else Screen.Detail(id, v.getOrNull(2)?.toLongOrNull() ?: 0L)
            }
            "edit" -> Screen.Edit(
                entryId = v.getOrNull(1)?.takeIf { it.isNotBlank() },
                fromDetail = v.getOrNull(2) == "1"
            )
            else -> Screen.List
        }
    }
)

/**
 * 页面键，给可保存状态槽用。
 *
 * 同一页每次进来必须是同一个键（详情页按条目区分），否则"暂存"会串到别的页面上。
 * 详情页带上 `entryId`：A 的详情和 B 的详情不该共用一份滚动位置。
 */
private fun Screen.key(): String = when (this) {
    Screen.List -> "list"
    Screen.Pending -> "pending"
    Screen.Trash -> "trash"
    Screen.Generator -> "generator"
    Screen.Categories -> "categories"
    Screen.Settings -> "settings"
    Screen.Backup -> "backup"
    Screen.Audit -> "audit"
    Screen.Security -> "security"
    is Screen.Detail -> "detail:$entryId"
    is Screen.Edit -> "edit:${entryId ?: "new"}"
}

/** 开库三态。 */
private sealed interface OpenState {
    data object Loading : OpenState
    data object Ready : OpenState
    data class Failed(val message: String, val canReset: Boolean) : OpenState
}

@Composable
fun VaultApp(
    openPendingSignal: Int = 0,
    sharedText: String? = null,
    onSharedTextConsumed: () -> Unit = {},
    /** `vaultbook://new` / `vaultbook://generator`，桌面快捷方式用。 */
    deepLinkTarget: String? = null,
    deepLinkSignal: Int = 0,
    /**
     * `vaultbook://copy/<entryId>`，桌面小组件点某一行时用。
     *
     * 打开详情页并**立刻复制密码** —— 小组件上点一下的本意就是"我要这个密码"，
     * 进去还要再点一次复制就白省了那几次点击。仍然会停在详情页，
     * 让用户看见复制了什么（而不是复制完就黑屏返回桌面）。
     */
    copyEntryId: String? = null,
    copySignal: Int = 0,
    onCopyConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val gateway = app.gateway
    val scope = rememberCoroutineScope()

    // 列表滚动位置放在这一层。列表页进详情会被销毁，位置留在页面内部就跟着没了 ——
    // "滚到第 80 条、点进详情、返回"会直接跳回第 1 条。
    // 用 Saver 只存"第一个可见项的下标 + 偏移"，转屏与进程恢复也能回到原处。
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    /** 每页一份可保存状态槽（见下面 `AnimatedContent` 里的说明）。 */
    val saveableStates = rememberSaveableStateHolder()

    /**
     * 已经播过入场动画的条目 id。
     *
     * 放在这一层而不是列表页内部：进详情会销毁列表页，返回时重新组合，
     * 若集合跟着重置，一屏卡片会再从头播一遍错落动画 —— 看着像刚刷新过。
     */
    val playedIds = remember { mutableSetOf<String>() }

    var openState by remember { mutableStateOf<OpenState>(OpenState.Loading) }
    var retryToken by remember { mutableIntStateOf(0) }
    var screen by rememberSaveable(stateSaver = ScreenSaver) {
        mutableStateOf<Screen>(Screen.List)
    }
    var pendingCount by remember { mutableIntStateOf(0) }
    var confirmReset by remember { mutableStateOf(false) }

    // 开库。retryToken 变化时重跑（"重试"按钮）。
    LaunchedEffect(retryToken) {
        openState = OpenState.Loading
        openState = gateway.openSimple().fold(
            onSuccess = { OpenState.Ready },
            onFailure = { error -> OpenState.Failed(describeOpenFailure(error), true) }
        )
    }

    // 通知点进来：信号每递增一次就跳一次待确认页。
    // 用递增计数而不是布尔值 —— 布尔值连续两次置 true 不会触发重组。
    LaunchedEffect(openPendingSignal, openState) {
        if (openPendingSignal > 0 && openState is OpenState.Ready) screen = Screen.Pending
    }

    // 外部分享进来：直接打开新建页，文本交给编辑页去识别。
    // 不在列表页就地解析 —— 用户点「分享到VaultBook」是想把这条存下来，
    // 不是想搜一下；开个已经填好的新建页，他确认一下就完事。
    LaunchedEffect(sharedText, openState) {
        if (!sharedText.isNullOrBlank() && openState is OpenState.Ready) {
            screen = Screen.Edit(null)
        }
    }

    // 桌面快捷方式：长按图标点「新建」或「生成密码」。
    // 等 openState 就绪再跳 —— 库还没打开时切到编辑页会拿到 null 的 repository。
    LaunchedEffect(deepLinkSignal, openState) {
        if (deepLinkSignal <= 0 || openState !is OpenState.Ready) return@LaunchedEffect
        when (deepLinkTarget) {
            "new" -> screen = Screen.Edit(null)
            "generator" -> screen = Screen.Generator
        }
    }

    // 桌面小组件：点某一行 → 进详情并自动复制。
    // 同样等 openState 就绪 —— 库没打开时进详情会读到空记录。
    LaunchedEffect(copySignal, openState) {
        val id = copyEntryId
        if (copySignal <= 0 || id.isNullOrBlank() || openState !is OpenState.Ready) {
            return@LaunchedEffect
        }
        screen = Screen.Detail(id, autoCopy = true, autoCopyToken = copySignal)
        onCopyConsumed()
    }

    // 通知权限。拒绝也不影响捕获与保存，只是捕获后不弹提示
    // （用户仍能在列表页右上角的角标看到数量）。
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 结果不需要处理，用的时候会再查一次权限 */ }

    LaunchedEffect(openState) {
        if (openState is OpenState.Ready && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 待确认角标。用一次性查询而不是 Flow：
    // 这个数字只在切页面时需要准，为它长期订阅一张表不划算。
    LaunchedEffect(openState, screen) {
        pendingCount = if (openState is OpenState.Ready) {
            runCatching { gateway.captureRepositoryOrNull()?.countPending() ?: 0 }.getOrDefault(0)
        } else {
            0
        }
    }

    when (val current = openState) {
        OpenState.Loading -> LoadingScreen()
        is OpenState.Failed -> OpenFailedScreen(
            message = current.message,
            canReset = current.canReset,
            onRetry = { retryToken++ },
            onReset = { confirmReset = true }
        )

        OpenState.Ready -> AnimatedContent(
            targetState = screen,
            // 新页从右侧轻微滑入 + 淡入，旧页反向淡出。
            // 位移只给宽度的 1/12（约 90px），是"有点方向感"而不是"整屏飞进来"——
            // 后者每次点进详情都像翻了一页 PPT，用久了很累。
            transitionSpec = {
                (
                    fadeIn(animationSpec = tween(Motion.Base)) +
                        slideInHorizontally(animationSpec = tween(Motion.Base)) { full -> full / 12 }
                    ).togetherWith(
                    fadeOut(animationSpec = tween(Motion.Fast)) +
                        slideOutHorizontally(animationSpec = tween(Motion.Fast)) { full -> -full / 24 }
                )
            },
            label = "screen"
        ) { page ->
            // 每页一个独立的可保存状态槽：搜索词、筛选条、滚动位置在离开页面时暂存，
            // 回来时原样放回。不加这层的话，"搜到一半点进详情、返回"，
            // 搜索框会空掉、筛选跳回"全部"，等于白搜一次。
            saveableStates.SaveableStateProvider(page.key()) {
            when (page) {
            Screen.List -> EntryListScreen(
                gateway = gateway,
                onOpenEntry = { id -> screen = Screen.Detail(id) },
                onCreateEntry = { screen = Screen.Edit(null) },
                // 从列表长按动作单直接进编辑：不经过详情页，
                // "我要改个错别字"不该先看一眼只读页面。
                onEditEntry = { id -> screen = Screen.Edit(id, fromDetail = false) },
                pendingCount = pendingCount,
                onOpenPending = { screen = Screen.Pending },
                onOpenTrash = { screen = Screen.Trash },
                onOpenGenerator = { screen = Screen.Generator },
                onOpenCategories = { screen = Screen.Categories },
                onOpenSettings = { screen = Screen.Settings },
                listState = listState,
                playedIds = playedIds
            )

            Screen.Pending -> {
                BackHandler { screen = Screen.List }
                PendingCaptureScreen(gateway = gateway, onBack = { screen = Screen.List })
            }

            Screen.Trash -> {
                BackHandler { screen = Screen.List }
                TrashScreen(gateway = gateway, onBack = { screen = Screen.List })
            }

            Screen.Generator -> {
                BackHandler { screen = Screen.List }
                PasswordGeneratorScreen(onBack = { screen = Screen.List })
            }

            Screen.Categories -> {
                BackHandler { screen = Screen.List }
                CategoryManageScreen(gateway = gateway, onBack = { screen = Screen.List })
            }

            Screen.Settings -> {
                BackHandler { screen = Screen.List }
                SettingsScreen(
                    gateway = gateway,
                    onBack = { screen = Screen.List },
                    onOpenBackup = { screen = Screen.Backup },
                    onOpenAudit = { screen = Screen.Audit },
                    onOpenCategories = { screen = Screen.Categories },
                    onOpenSecurity = { screen = Screen.Security }
                )
            }

            Screen.Backup -> {
                // 从设置进来的，返回也回设置 —— 直接回列表会让用户以为跳错了
                BackHandler { screen = Screen.Settings }
                BackupScreen(gateway = gateway, onBack = { screen = Screen.Settings })
            }

            Screen.Audit -> {
                BackHandler { screen = Screen.Settings }
                AuditLogScreen(gateway = gateway, onBack = { screen = Screen.Settings })
            }

            Screen.Security -> {
                BackHandler { screen = Screen.Settings }
                SecurityCheckScreen(
                    gateway = gateway,
                    onBack = { screen = Screen.Settings },
                    onOpenEntry = { id -> screen = Screen.Detail(id) }
                )
            }

            is Screen.Detail -> {
                BackHandler { screen = Screen.List }
                EntryDetailScreen(
                    gateway = gateway,
                    entryId = page.entryId,
                    onBack = { screen = Screen.List },
                    onEdit = { screen = Screen.Edit(page.entryId, fromDetail = true) },
                    onDeleted = { screen = Screen.List },
                    reloadKey = page.reloadKey,
                    autoCopy = page.autoCopy,
                    autoCopyToken = page.autoCopyToken
                )
            }

            is Screen.Edit -> {
                BackHandler {
                    screen = if (page.fromDetail && page.entryId != null) {
                        Screen.Detail(page.entryId, System.currentTimeMillis())
                    } else {
                        Screen.List
                    }
                }
                EntryEditScreen(
                    gateway = gateway,
                    entryId = page.entryId,
                    onDone = {
                        // 从详情进来的编辑，保存后回到详情（带新 key 触发重载）；
                        // 新建完直接回列表 —— 新建的条目还没有任何"详情"可看，
                        // 强行跳过去用户会觉得莫名其妙。
                        screen = if (page.fromDetail && page.entryId != null) {
                            Screen.Detail(page.entryId, System.currentTimeMillis())
                        } else {
                            Screen.List
                        }
                    },
                    // 删除后详情页已经不存在了，必须回列表
                    onDeleted = { screen = Screen.List },
                    // 只在新建时把分享文本交给编辑页；编辑已有条目时分享文本与他无关
                    sharedText = if (page.entryId == null) sharedText else null,
                    onSharedTextConsumed = onSharedTextConsumed
                )
            }
            }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("新建一个空库？") },
            text = {
                Text(
                    "当前数据库打不开，只能新建一个空的。原来的 vault.db 文件会被删除，" +
                        "里面的记录不会保留。\n\n如果这些记录你还想找回来，先别点确认 —— " +
                        "去设置里把 App 的文件（databases 和 shared_prefs 两个目录）拷贝出来再说。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    scope.launch {
                        gateway.resetSimpleVault()
                        retryToken++
                    }
                }) {
                    Text("确认新建", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("我再想想") }
            }
        )
    }
}

/**
 * 把开库异常翻译成用户能动作的话。
 *
 * 关键是别把所有失败都说成"数据损坏" —— 那会让用户直接放弃，
 * 而其中最常见的一种（只拷了库没拷口令）其实还有救。
 */
private fun describeOpenFailure(error: Throwable): String = when (error) {
    is DbKeyMissingException -> error.message
        ?: "数据库文件在，但开库口令丢了。请把 shared_prefs 目录一起拷过来。"

    else -> buildString {
        append("打开数据库失败。")
        error.message?.takeIf { it.isNotBlank() }?.let { append("\n\n技术信息：").append(it) }
    }
}

@Composable
private fun LoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("正在打开…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun OpenFailedScreen(
    message: String,
    canReset: Boolean,
    onRetry: () -> Unit,
    onReset: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "打不开",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(16.dp))
        Card(
            modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Text(
                text = message,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Start
            )
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry, modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth()) {
            Text("重试")
        }
        if (canReset) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onReset) {
                Text("新建一个空库", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
