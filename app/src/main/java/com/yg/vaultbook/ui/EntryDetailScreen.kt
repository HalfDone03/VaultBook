package com.yg.vaultbook.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yg.vaultbook.core.util.PasswordStrength
import com.yg.vaultbook.data.VaultGateway
import com.yg.vaultbook.data.entity.AuditLogEntity
import com.yg.vaultbook.domain.EntryDetail
import com.yg.vaultbook.domain.PlainCategory
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 条目详情。
 *
 * ── 为什么密码默认不显示 ──────────────────────────────────────
 * `revealPassword` 会把密文解成明文字符放进内存。详情页是"随便看看"的场景，
 * 绝大多数打开都不是为了看密码，所以默认不点就不解密 —— 少一次解密就少一次暴露。
 *
 * ── 内存清理 ──────────────────────────────────────────────────
 * 所有解出来的 `CharArray`（当前密码、历史密码）都挂在 [DisposableEffect] 上，
 * 数组一换或页面一销毁就 `fill('\u0000')`。唯一的例外是交给剪贴板的那一份：
 * 剪贴板只能存 `String`，而 `String` 无法清零。这是 Android API 的硬限制，
 * 不是遗漏（见 [copySecret] 的 30 秒自动清除作为补偿）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryDetailScreen(
    gateway: VaultGateway,
    entryId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDeleted: () -> Unit,
    /** 从编辑页返回时递增，用来强制重新加载（`entryId` 本身没变）。 */
    reloadKey: Long = 0L,
    /**
     * 进来就自动复制一次密码。桌面小组件点某一行时置 true。
     *
     * 为什么不在小组件里直接复制：Android 10 起后台进程写剪贴板会被系统
     * 静默丢弃，BroadcastReceiver 正是后台进程。只能拉起前台再复制。
     */
    autoCopy: Boolean = false,
    /** 每次从小组件点进来都递增，让自动复制能重复触发。 */
    autoCopyToken: Int = 0
) {
    val repo = remember(gateway) { gateway.repositoryOrNull() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (repo == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("保险库未解锁", color = MaterialTheme.colorScheme.error)
        }
        return
    }

    var detail by remember { mutableStateOf<EntryDetail?>(null) }
    var categories by remember { mutableStateOf<List<PlainCategory>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var missing by remember { mutableStateOf(false) }

    var plain by remember { mutableStateOf<CharArray?>(null) }
    var history by remember { mutableStateOf<List<CharArray>>(emptyList()) }
    var showHistory by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    // 数组一换就清旧的。页面销毁时 onDispose 清最后一个。
    DisposableEffect(plain) {
        val snapshot = plain
        onDispose { snapshot?.fill('\u0000') }
    }
    DisposableEffect(history) {
        val snapshot = history
        onDispose { snapshot.forEach { it.fill('\u0000') } }
    }

    LaunchedEffect(entryId, reloadKey) {
        val loaded = repo.loadDetail(entryId)
        detail = loaded
        missing = loaded == null
        categories = runCatching { repo.categories() }.getOrDefault(emptyList())
        // 密码**直接显示**：这个 App 的定位是"打开就要看到"，
        // 藏起来再让用户点一下"显示"，等于每一步都多一次点击。
        // 想看的时候它就在那儿，是这类工具最基本的期待。
        plain = runCatching { repo.revealPassword(entryId) }.getOrNull()
        // 打开详情就算"用过"：列表排序按 last_used_at 走，查过一次的条目
        // 下次应该自己浮到前面（速查本的典型用法是十分钟内看同一条两三次）。
        runCatching { repo.markUsed(entryId) }
        loading = false
    }

    val summary = detail?.summary
    val categoryName = remember(categories, summary?.categoryId) {
        categories.firstOrNull { it.id == summary?.categoryId }?.name
    }

    fun toggleReveal() {
        val current = plain
        if (current != null) {
            current.fill('\u0000')
            plain = null
            return
        }
        scope.launch {
            plain = runCatching { repo.revealPassword(entryId) }.getOrNull()
        }
    }

    fun copyPassword() {
        scope.launch {
            val pwd = runCatching { repo.revealPassword(entryId) }.getOrNull() ?: return@launch
            try {
                copySecret(context, "密码", String(pwd))
                repo.audit(AuditLogEntity.EVENT_COPY, entryId, "password")
                // 自己弹一条 Snackbar，而不只依赖 copySecret 里的 toast：
                // API 33 起系统不再让后台 toast 弹出，而列表排序又用 last_used_at，
                // 复制后这一条会往上跳 —— 用户需要一个明确的"成了"的确认，
                // 否则会以为是自己点错了、列表才会动。
                snackbar.showSnackbar("密码已复制，30 秒后自动清空剪贴板")
            } finally {
                pwd.fill('\u0000')
            }
        }
    }

    // 小组件点进来时自动复制一次。
    //
    // key 里带 loading：加载完成那一刻（true→false）才真正执行，
    // 否则会在 detail 还没读出来时就调 copyPassword。
    //
    // key 里带 autoCopyToken：用户从桌面重复点同一行时，Screen.Detail 的
    // entryId 与 reloadKey 都一样、state 不会变，只能靠这个自增 token
    // 让 LaunchedEffect 再跑一次。
    LaunchedEffect(entryId, reloadKey, autoCopy, autoCopyToken, loading) {
        if (!autoCopy || loading) return@LaunchedEffect
        copyPassword()
    }

    fun toggleHistory() {        if (showHistory) {
            showHistory = false
            history = emptyList()
            return
        }
        scope.launch {
            history = runCatching { repo.passwordHistory(entryId) }.getOrDefault(emptyList())
            showHistory = history.isNotEmpty()
            if (history.isEmpty()) message = "没有历史密码"
        }
    }

    fun openUrl(url: String) {
        val target = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
        } catch (e: ActivityNotFoundException) {
            message = "没有能打开这个网址的应用"
        }
    }

    fun delete() {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { repo.trashEntry(entryId) }
            busy = false
            onDeleted()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            // 底部常驻"复制密码"：详情页存在的主要目的就是拿密码走人。
            // 原来要滚到密码那一行、再找到右侧的小"复制"文字按钮 ——
            // 一条记录内容长的时候，主操作可能根本不在第一屏。
            val current = summary
            if (!loading && !missing && current != null && current.hasPassword) {
                Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { copyPassword() },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("复制密码", style = MaterialTheme.typography.labelLarge)
                        }
                        if (current.url != null) {
                            OutlinedButton(
                                onClick = { openUrl(current.url) },
                                modifier = Modifier.height(50.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text("打开", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        },
        topBar = {
            VaultTopBar(
                title = "详情",
                subtitle = summary?.let { if (it.title.isBlank()) "未命名" else it.title },
                onBack = onBack,
                actions = {
                    TopBarButton(
                        icon = Icons.Filled.Edit,
                        description = "编辑",
                        enabled = !loading && !missing,
                        onClick = onEdit
                    )
                    Spacer(Modifier.width(8.dp))
                    TopBarButton(
                        icon = Icons.Filled.Delete,
                        description = "删除",
                        enabled = !loading && !missing && !busy,
                        tint = MaterialTheme.colorScheme.error,
                        onClick = { delete() }
                    )
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        when {
            loading -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) { Text("读取中…", color = MaterialTheme.colorScheme.onSurfaceVariant) }

            missing || summary == null -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) { Text("这条记录已不存在", color = MaterialTheme.colorScheme.onSurfaceVariant) }

            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Header(
                    // seed 用站点标识优先，和列表页头像颜色保持一致
                    title = summary.title,
                    seed = summary.hostNorm ?: summary.packageNorm ?: summary.title,
                    categoryName = categoryName,
                    strengthScore = summary.strengthScore,
                    hasPassword = summary.hasPassword,
                    favorite = summary.favorite
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                ) {
                    Column(Modifier.padding(16.dp)) {
                        summary.username?.let { user ->
                            DetailRow(
                                label = "账号",
                                value = user,
                                monospace = true,
                                onCopy = {
                                    copyPlainText(context, "账号", user)
                                    scope.launch { repo.audit(AuditLogEntity.EVENT_COPY, entryId, "username") }
                                }
                            )
                            HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        }

                        PasswordRow(
                            plain = plain,
                            hasPassword = summary.hasPassword,
                            onToggle = { toggleReveal() },
                            onCopy = { copyPassword() }
                        )

                        summary.url?.let { url ->
                            HorizontalDivider(Modifier.padding(vertical = 10.dp))
                            DetailRow(
                                label = "网址",
                                value = url,
                                monospace = true,
                                onClick = { openUrl(url) },
                                onCopy = {
                                    copyPlainText(context, "网址", url)
                                }
                            )
                        }
                    }
                }

                // 动态码单独一张卡：它每 30 秒变一次，混在"备注/密钥"卡片里
                // 会跟着一起重组，也让"这是会过期的东西"这件事不明显。
                val totpSecret = detail?.totp?.takeIf { it.isNotBlank() }
                if (totpSecret != null) {
                    Spacer(Modifier.height(12.dp))
                    TotpCard(secretText = totpSecret)
                }

                if (!detail?.notes.isNullOrBlank() || detail?.customFields?.isNotEmpty() == true
                ) {
                    Spacer(Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            // 这里不用局部函数递归加分割线：Compose 不允许在
                            // 局部函数里调用 composable，只能把这几个块显式展开。
                            val notes = detail?.notes?.takeIf { it.isNotBlank() }
                            val fields = detail?.customFields.orEmpty()

                            if (notes != null) {
                                DetailRow(label = "备注", value = notes)
                                if (fields.isNotEmpty()) {
                                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                                }
                            }
                            fields.forEachIndexed { index, field ->
                                DetailRow(
                                    label = field.label,
                                    value = if (field.type == "secret") "••••••••" else field.value,
                                    monospace = field.type != "multiline",
                                    onCopy = if (field.type == "secret") {
                                        { copySecret(context, field.label, field.value) }
                                    } else {
                                        { copyPlainText(context, field.label, field.value) }
                                    }
                                )
                                if (index < fields.lastIndex) {
                                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                                }
                            }
                        }
                    }
                }

                if (summary.hasPassword) {
                    Spacer(Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { toggleHistory() }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (showHistory) "隐藏历史密码" else "查看历史密码",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = if (showHistory) "收起" else "展开",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (showHistory) {
                                HorizontalDivider()
                                history.forEachIndexed { index, pwd ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "#${index + 1}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.width(32.dp)
                                        )
                                        Text(
                                            text = "•".repeat(pwd.size.coerceIn(6, 16)),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            onClick = {
                                                copySecret(context, "历史密码", String(pwd))
                                                scope.launch {
                                                    repo.audit(
                                                        AuditLogEntity.EVENT_COPY, entryId, "password history"
                                                    )
                                                }
                                            }
                                        ) { Text("复制") }
                                    }
                                }
                                Text(
                                    text = "只保留最近 20 条。历史密码无法回滚成当前密码。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = buildString {
                        append("创建 ")
                        append(timeText(summary.createdAt))
                        append("　更新 ")
                        append(timeText(summary.updatedAt))
                        summary.pwdUpdatedAt?.let {
                            append("\n密码改动 ")
                            append(timeText(it))
                        }
                        summary.lastUsedAt?.let {
                            append("　用过 ")
                            append(summary.useCount)
                            append(" 次，最后 ")
                            append(timeText(it))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )

                message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))
                // 编辑入口在正文里再放一颗。
                // 顶栏那颗是图标，用户反馈"点编辑只能改标题"，说明他压根没找到
                // 或者点错了；写清"编辑"两个字放在正文末尾，比图标可靠。
                OutlinedButton(
                    onClick = onEdit,
                    enabled = !loading && !missing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("编辑这条（标题、账号、密码、网址、备注都能改）")
                }

                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { delete() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("删除（进回收站，30 天后彻底清除）", color = MaterialTheme.colorScheme.error)
                }

                Spacer(Modifier.height(48.dp))
            }
        }
    }
}

@Composable
private fun Header(
    title: String,
    seed: String,
    categoryName: String?,
    strengthScore: Int,
    hasPassword: Boolean,
    favorite: Boolean
) {
    Row(
        Modifier
            .fillMaxWidth()
            // 详情页顶部做成一块带色的面板：列表点进来如果整页还是白底，
            // 会像"换了一个程序"。这一块颜色把两条路径接上。
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.03f)
                    )
                )
            )
            .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 详情页也带一块渐变头像，和列表页那一行对上，点进来不会觉得换了套 UI
        EntryAvatar(title = title, seed = seed, size = 56.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (favorite) {
                    Spacer(Modifier.width(6.dp))
                    Text("★", color = MaterialTheme.colorScheme.secondary)
                }
            }
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (hasPassword) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    strengthScore <= 1 -> MaterialTheme.colorScheme.error
                                    strengthScore == 2 -> MaterialTheme.colorScheme.secondary
                                    else -> MaterialTheme.colorScheme.primary
                                }
                            )
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = PasswordStrength.labelOf(strengthScore),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "无密码",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                categoryName?.let {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "· $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    monospace: Boolean = false,
    onClick: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
            )
        }
        onCopy?.let {
            CopyGhost(onClick = it)
        }
    }
}

/**
 * 复制按钮。
 *
 * 原来用 TextButton，主色文字贴在白卡片上和正文抢注意力；
 * 改成淡底小胶囊，边界收在浅色里，视线还是落在左边的值上。
 */
@Composable
private fun CopyGhost(
    text: String = "复制",
    color: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

@Composable
private fun PasswordRow(
    plain: CharArray?,
    hasPassword: Boolean,
    onToggle: () -> Unit,
    onCopy: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "密码",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            // 密码是"打开这条记录最想看的东西"，所以给它最大的字和一点底色。
            // Monospace + 字距是为了让 l/1、O/0 这类字符一眼能分开。
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (hasPassword) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color.Transparent
                }
            ) {
                Text(
                    text = when {
                        !hasPassword -> "（没有保存密码）"
                        plain != null -> String(plain)
                        else -> "••••••••••"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.2.sp,
                    color = if (hasPassword) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(
                        horizontal = if (hasPassword) 10.dp else 0.dp,
                        vertical = if (hasPassword) 5.dp else 0.dp
                    )
                )
            }
        }
        if (hasPassword) {
            CopyGhost(
                text = if (plain != null) "隐藏" else "显示",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onToggle
            )
            Spacer(Modifier.width(6.dp))
            CopyGhost(onClick = onCopy)
        }
    }
}

private fun timeText(epochMs: Long): String {
    // 每次调用新建：把 Locale.getDefault() 定格在静态字段里，用户中途切语言就会显示旧格式
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
}
