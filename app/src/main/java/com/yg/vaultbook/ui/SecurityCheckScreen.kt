package com.yg.vaultbook.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yg.vaultbook.data.SecurityIssue
import com.yg.vaultbook.data.SecurityIssueKind
import com.yg.vaultbook.data.SecurityReport
import com.yg.vaultbook.data.VaultGateway

/**
 * 安全体检页。
 *
 * 只做「看」，不自动改：把风险摊开让用户自己决定先动哪一个。
 * 每条都能点进去，落到具体条目上才改得动 —— 光看一个数字没有用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityCheckScreen(
    gateway: VaultGateway,
    onBack: () -> Unit,
    onOpenEntry: (String) -> Unit
) {
    val repo = remember(gateway) { gateway.repositoryOrNull() }
    var report by remember { mutableStateOf<SecurityReport?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(repo) {
        if (repo == null) return@LaunchedEffect
        runCatching { repo.securityReport() }
            .onSuccess { report = it }
            .onFailure { failure = it.message ?: "体检没有跑完" }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("安全体检", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val r = report
            when {
                failure != null -> Text(
                    "体检没跑完：$failure",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )

                r == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("  正在把库读一遍…", style = MaterialTheme.typography.bodyMedium)
                }

                else -> {
                    SummaryCard(r)
                    if (r.isEmpty) {
                        Text(
                            "没有要改的。弱密码、重复密码、半年没换的密码，这三样一个都没碰上。",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        IssueGroup(r, SecurityIssueKind.WEAK, "太弱的密码", onOpenEntry)
                        IssueGroup(r, SecurityIssueKind.REUSED, "重复使用的密码", onOpenEntry)
                        IssueGroup(r, SecurityIssueKind.OLD, "很久没换的密码", onOpenEntry)
                        IssueGroup(r, SecurityIssueKind.NO_PASSWORD, "没存密码的条目", onOpenEntry)
                        Text(
                            "改密码在条目里点「编辑」就行。同一个密码用在两个地方，" +
                                "一个站被撞库，另一处也跟着完了 —— 优先拆开这些。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SummaryCard(r: SecurityReport) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("一共 ${r.total} 条，其中 ${r.withPassword} 条存了密码", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                if (r.isEmpty) "体检通过" else "${r.issues.size} 处可以改",
                style = MaterialTheme.typography.titleLarge,
                color = if (r.isEmpty) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun IssueGroup(
    r: SecurityReport,
    kind: SecurityIssueKind,
    title: String,
    onOpenEntry: (String) -> Unit
) {
    val items = r.issuesOf(kind)
    if (items.isEmpty()) return

    Text(
        "$title · ${items.size}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            items.forEachIndexed { index, issue ->
                if (index > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                IssueRow(issue, onOpenEntry)
            }
        }
    }
}

@Composable
private fun IssueRow(issue: SecurityIssue, onOpenEntry: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenEntry(issue.entryId) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(issue.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                issue.username ?: issue.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (issue.username != null) {
                Text(
                    issue.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}