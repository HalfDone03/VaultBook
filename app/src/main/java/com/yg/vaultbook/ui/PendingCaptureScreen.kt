package com.yg.vaultbook.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yg.vaultbook.core.matcher.CaptureConfidence
import com.yg.vaultbook.data.VaultGateway
import com.yg.vaultbook.domain.PendingCaptureSummary
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「待确认」列表 —— 自动捕获到的账号密码在这里等用户点头。
 *
 * 为什么必须有这一页：捕获是无感的，用户不知道刚才在浏览器里输的密码被记下来了。
 * 不给他一个确认环节，等于"任何在登录框里输过的密码都自动进了保险库"，
 * 其中可能包括输错的、别人的、或者他根本不想存的。
 *
 * 保存动作会做三件事：用 DEK 重新加密写进正式条目 → 建立站点映射 → 删掉待确认记录。
 * 也就是说 Keystore 密文不会长期留存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingCaptureScreen(gateway: VaultGateway, onBack: () -> Unit) {
    val captureRepo = remember(gateway) { gateway.captureRepositoryOrNull() }
    val vaultRepo = remember(gateway) { gateway.repositoryOrNull() }
    val scope = rememberCoroutineScope()

    if (captureRepo == null || vaultRepo == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("保险库未解锁", color = MaterialTheme.colorScheme.error)
        }
        return
    }

    val flow = remember(captureRepo) { captureRepo.observePending() }
    val items by flow.collectAsState(initial = emptyList())

    var notice by remember { mutableStateOf<String?>(null) }

    // 进页面时清一次过期项：这些记录用户永远不会处理，留着只会让计数虚高
    LaunchedEffect(captureRepo) {
        runCatching { captureRepo.purgeExpired() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            VaultTopBar(
                title = "待确认",
                subtitle = "共 ${items.size} 条",
                onBack = onBack,
                backIcon = Icons.Filled.Close,
                backLabel = "关闭"
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            notice?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("没有待确认的捕获", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "在别处登录网站后，这里会出现新的账号密码",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                return@Column
            }

            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    CaptureRow(
                        item = item,
                        onAccept = {
                            scope.launch {
                                val created = runCatching {
                                    captureRepo.accept(item.id, vaultRepo)
                                }.getOrNull()
                                notice = if (created != null) "已保存到保险库" else "保存失败"
                            }
                        },
                        onDismiss = {
                            scope.launch {
                                runCatching { captureRepo.dismiss(item.id) }
                            }
                        },
                        onSnooze = {
                            scope.launch {
                                val host = item.hostNorm
                                if (host == null) {
                                    // 没有域名（原生 App 捕获）没法按站点静默，
                                    // 只能忽略这一条
                                    runCatching { captureRepo.dismiss(item.id) }
                                    notice = "已忽略这一条"
                                } else {
                                    runCatching { captureRepo.snooze(host) }
                                    runCatching { captureRepo.dismiss(item.id) }
                                    notice = "24 小时内不再提示 ${item.siteLabel()}"
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CaptureRow(
    item: PendingCaptureSummary,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit
) {
    val strong = item.confidence >= CaptureConfidence.STRONG_THRESHOLD

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.title?.takeIf { it.isNotBlank() } ?: item.siteLabel(),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitle = buildString {
                        append(item.siteLabel())
                        item.username?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = if (strong) "高可信" else "待确认",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (strong) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = "${timeText(item.createdAt)} · 来自${sourceLabel(item.source)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onAccept) { Text("保存") }
                TextButton(onClick = onDismiss) { Text("忽略") }
                TextButton(onClick = onSnooze) { Text("静默 24 小时") }
            }
        }
    }
}

private fun sourceLabel(source: String): String = when (source) {
    "autofill" -> "自动填充"
    "a11y" -> "无障碍捕获"
    "webview" -> "网页内捕获"
    else -> source
}

private fun timeText(epochMs: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
