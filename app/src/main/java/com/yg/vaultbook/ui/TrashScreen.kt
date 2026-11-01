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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yg.vaultbook.data.VaultGateway
import com.yg.vaultbook.data.VaultRepository
import com.yg.vaultbook.domain.EntrySummary
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 回收站。
 *
 * 删除是软删除（`deleted_at` 置位），条目在这里保留 30 天。
 * 进页面先跑一次 [VaultRepository.purgeExpiredTrash] —— 到期清理不需要 WorkManager，
 * "用户打开回收站"就是最自然的时机。
 *
 * 「清空」与「彻底删除」都会二次确认：物理删除后 `entry_history`、
 * `custom_fields`、`site_mappings` 会跟着 `ON DELETE CASCADE` 一起没，
 * 连密码历史都找不回来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    gateway: VaultGateway,
    onBack: () -> Unit,
    onRestored: () -> Unit = {}
) {
    val repo = remember(gateway) { gateway.repositoryOrNull() }
    val scope = rememberCoroutineScope()

    if (repo == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("保险库未解锁", color = MaterialTheme.colorScheme.error)
        }
        return
    }

    var items by remember { mutableStateOf<List<EntrySummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var confirmPurge by remember { mutableStateOf<PurgeTarget?>(null) }

    LaunchedEffect(reload) {
        // 到期清理只在首次进入时做；手动刷新（reload>0）不再清，
        // 否则用户刚恢复的条目可能因为时钟微差被再次判为过期。
        if (reload == 0) runCatching { repo.purgeExpiredTrash() }
        items = runCatching { repo.loadTrashed() }.getOrDefault(emptyList())
        loading = false
    }

    fun restore(id: String) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { repo.restoreEntry(id) }
            busy = false
            reload++
            onRestored()
        }
    }

    fun purge(id: String) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { repo.purgeEntry(id) }
            busy = false
            reload++
        }
    }

    fun purgeAll() {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { repo.purgeAllTrashed() }
            busy = false
            reload++
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("回收站", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "共 ${items.size} 条",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (items.isNotEmpty()) {
                        TextButton(
                            onClick = { confirmPurge = PurgeTarget.All },
                            enabled = !busy
                        ) {
                            Text("清空", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("读取中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "回收站是空的",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "删除的条目会在这里保留 30 天",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items, key = { it.id }) { entry ->
                        TrashRow(
                            entry = entry,
                            onRestore = { restore(entry.id) },
                            onPurge = { confirmPurge = PurgeTarget.One(entry.id) }
                        )
                    }
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "这里的条目 30 天后会自动彻底清除。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            }
        }
    }

    val target = confirmPurge
    if (target != null) {
        val isAll = target is PurgeTarget.All
        AlertDialog(
            onDismissRequest = { confirmPurge = null },
            title = { Text(if (isAll) "清空回收站？" else "彻底删除这一条？") },
            text = {
                Text(
                    if (isAll) {
                        "这 ${items.size} 条记录及其密码历史会被永久删除，无法恢复。"
                    } else {
                        "这条记录及其密码历史会被永久删除，无法恢复。想保留就先恢复。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmPurge = null
                    when (target) {
                        is PurgeTarget.All -> purgeAll()
                        is PurgeTarget.One -> purge(target.id)
                    }
                }) { Text("彻底删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmPurge = null }) { Text("取消") }
            }
        )
    }
}

private sealed interface PurgeTarget {
    data object All : PurgeTarget
    data class One(val id: String) : PurgeTarget
}

@Composable
private fun TrashRow(entry: EntrySummary, onRestore: () -> Unit, onPurge: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = listOfNotNull(entry.username, entry.hostNorm).joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = remainingText(entry.deletedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onRestore) { Text("恢复") }
                TextButton(onClick = onPurge) {
                    Text("彻底删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private const val DAY_MS: Long = 24L * 60 * 60 * 1000

/**
 * 格式化删除时间。
 *
 * 刻意每次调用都新建 `SimpleDateFormat`，而不是存成静态字段：
 * 静态字段会把 `Locale.getDefault()` 定格在类初始化那一刻，
 * 用户中途切换系统语言后，这一行仍按旧语言排版（lint: ConstantLocale）。
 */

/**
 * 「还剩 N 天」。
 *
 * 用向上取整：剩 0.3 天也说"还剩 1 天"，而不是"还剩 0 天" ——
 * 后者看起来像已经过期但还躺在列表里，会让人以为清理坏了。
 */
private fun remainingText(deletedAt: Long?): String {
    if (deletedAt == null) return ""
    val deadline = deletedAt + VaultRepository.TRASH_RETENTION_MS
    val left = deadline - System.currentTimeMillis()
    val days = if (left <= 0) 0 else ((left + DAY_MS - 1) / DAY_MS).toInt()
    val whenText = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(deletedAt))
    return when {
        days <= 0 -> "$whenText 删除 · 即将清除"
        else -> "$whenText 删除 · 还剩 $days 天"
    }
}
