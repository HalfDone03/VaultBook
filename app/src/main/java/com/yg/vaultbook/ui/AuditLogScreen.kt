package com.yg.vaultbook.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.yg.vaultbook.data.VaultGateway
import com.yg.vaultbook.data.entity.AuditLogEntity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 审计日志页。
 *
 * ── 这个页面到底有什么用 ──────────────────────────────────────
 * 它不是给系统管理员看的，是给**用户自己**看的：某天你怀疑"是不是有人动过我的手机"，
 * 可以来这儿看有没有你没做过的"查看密码"或"复制密码"记录。
 *
 * 所以展示顺序按时间倒序，且**每条都带精确到分钟的时间**。
 *
 * ── 硬约束 ──────────────────────────────────────────────────
 * `detail` 里永远不含凭据（写入侧在 `VaultRepository.audit` 就保证了）。
 * 这里额外做一件事：targetId 只显示前 8 位。完整 UUID 对用户没有意义，
 * 但截图发出去时不该带上完整标识。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditLogScreen(gateway: VaultGateway, onBack: () -> Unit) {
    val repo = remember(gateway) { gateway.repositoryOrNull() }
    val scope = rememberCoroutineScope()

    var logs by remember { mutableStateOf<List<AuditLogEntity>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(repo, reload) {
        if (repo == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        logs = runCatching { repo.recentAudit(MAX_SHOWN) }.getOrDefault(emptyList())
        loading = false
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            VaultTopBar(
                title = "审计日志",
                subtitle = "共 ${logs.size} 条",
                onBack = onBack,
                actions = {
                    TopBarButton(
                        icon = Icons.Filled.Delete,
                        description = "清空日志",
                        enabled = logs.isNotEmpty() && repo != null,
                        tint = MaterialTheme.colorScheme.error,
                        onClick = { confirmClear = true }
                    )
                }
            )
        }
    ) { padding ->
        if (repo == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("保险库未解锁", color = MaterialTheme.colorScheme.error)
            }
            return@Scaffold
        }

        if (logs.isEmpty() && !loading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("还没有记录", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(6.dp))
                Text(
                    "解锁、查看密码、复制、删除都会记在这里",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(logs, key = { it.id }) { log ->
                LogRow(log)
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空审计日志？") },
            text = {
                Text(
                    "这 ${logs.size} 条记录会被永久删除。条目本身不受影响。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        scope.launch {
                            // 局部别名：`repo` 是可空字段，闭包里无法依赖前面的
                            // 早退做智能转换，这里显式取一次非空值
                            val r = repo ?: return@launch
                            runCatching { r.clearAudit() }
                            reload++
                        }
                    }
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun LogRow(log: AuditLogEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        eventLabel(log.event),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (log.event == AuditLogEntity.EVENT_UNLOCK_FAIL) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    log.targetId?.let {
                        Text(
                            "  #${it.take(8)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                log.detail?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                timeText(log.createdAt),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 最多展示的条数。数据库里保留 2000 条，但一次性渲染 2000 个 Card 会明显卡顿。 */
private const val MAX_SHOWN = 300

private fun eventLabel(event: String): String = when (event) {
    AuditLogEntity.EVENT_UNLOCK_OK -> "解锁成功"
    AuditLogEntity.EVENT_UNLOCK_FAIL -> "解锁失败"
    AuditLogEntity.EVENT_LOCK -> "自动锁定"
    AuditLogEntity.EVENT_COPY -> "复制"
    AuditLogEntity.EVENT_REVEAL -> "查看密码"
    AuditLogEntity.EVENT_CREATE -> "新建"
    AuditLogEntity.EVENT_UPDATE -> "修改"
    AuditLogEntity.EVENT_DELETE -> "删除"
    AuditLogEntity.EVENT_EXPORT -> "导出"
    AuditLogEntity.EVENT_IMPORT -> "导入"
    else -> event
}

/**
 * 时间格式化每次新建 `SimpleDateFormat`。
 *
 * 不用静态字段缓存：静态字段会把第一次调用时的 `Locale.getDefault()` 定格住，
 * 用户在系统设置里改了语言/地区之后，这个页面的时间格式不会跟着变
 * （lint 的 `ConstantLocale` 就是查这个）。
 */
private fun timeText(epochMs: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
