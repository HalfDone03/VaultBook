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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yg.vaultbook.BuildConfig
import com.yg.vaultbook.data.BackupReminder
import com.yg.vaultbook.data.VaultGateway

/**
 * 设置页。
 *
 * 这里只放"改变应用行为"的东西，不放数据操作（那些在各自页面里）。
 *
 * @param onOpenBackup 打开备份页
 * @param onOpenAudit 打开审计日志页
 * @param onOpenCategories 打开分类管理（也可从列表页进，这里放一个入口方便找）
 * @param onOpenSecurity 打开安全体检
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    gateway: VaultGateway,
    onBack: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenAudit: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenSecurity: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember(gateway) { gateway.repositoryOrNull() }

    if (repo == null) {
        LockedNotice(onBack)
        return
    }

    var entryCount by remember(repo) { mutableIntStateOf(-1) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(repo, reload) {
        entryCount = runCatching { repo.loadSummaries().size }.getOrDefault(-1)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置", style = MaterialTheme.typography.titleLarge) },
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
            SectionTitle("数据")
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    SettingRow(
                        title = "导出加密备份",
                        subtitle = BackupReminder.describe(context),
                        onClick = onOpenBackup
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingRow(
                        title = "导入备份",
                        subtitle = "从 .vbc 文件恢复，可选择性覆盖",
                        onClick = onOpenBackup
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingRow(
                        title = "分类管理",
                        subtitle = "改名、排序、删除",
                        onClick = onOpenCategories
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingRow(
                        title = "安全体检",
                        subtitle = "弱密码、重复密码、太久没换的密码",
                        onClick = onOpenSecurity
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingRow(
                        title = "审计日志",
                        subtitle = "谁在什么时候动过保险库",
                        onClick = onOpenAudit
                    )
                }
            }

            SectionTitle("关于")
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    InfoLine("版本", BuildConfig.VERSION_NAME)
                    InfoLine(
                        "条目",
                        if (entryCount < 0) "读取中…" else "$entryCount 条"
                    )
                    InfoLine("存储", "本机明文（与手机记事本同级）")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "数据只存在这台手机里，没有任何密码要记，打开就能看。"
                            + "代价是：能解锁这台手机、或能读到本应用文件的人，也能看到这些账号密码。"
                            + "所以别把手机随便借人，重要账号建议开启两步验证。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
    )
}

@Composable
private fun SettingRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun LockedNotice(onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("数据还没打开", color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "点这里返回重试",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.clickable(onClick = onBack)
        )
    }
}
