package com.yg.vaultbook.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    LaunchedEffect(repo) {
        entryCount = runCatching { repo.loadSummaries().size }.getOrDefault(-1)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            VaultTopBar(
                title = "设置",
                subtitle = "数据、外观与关于",
                onBack = onBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionTitle("数据")
            SettingsCard {
                SettingRow(
                    icon = Icons.Filled.Share,
                    accent = accentOf("backup"),
                    title = "导出加密备份",
                    subtitle = BackupReminder.describe(context),
                    onClick = onOpenBackup
                )
                RowDivider()
                SettingRow(
                    icon = Icons.Filled.Refresh,
                    accent = accentOf("import"),
                    title = "导入备份",
                    subtitle = "从 .vbc 文件恢复，可选择性覆盖",
                    onClick = onOpenBackup
                )
            }

            SectionTitle("整理")
            SettingsCard {
                SettingRow(
                    icon = Icons.Filled.Menu,
                    accent = accentOf("category"),
                    title = "分类管理",
                    subtitle = "改名、排序、删除",
                    onClick = onOpenCategories
                )
                RowDivider()
                SettingRow(
                    icon = Icons.Filled.Warning,
                    accent = accentOf("security"),
                    title = "安全体检",
                    subtitle = "弱密码、重复密码、太久没换的密码",
                    onClick = onOpenSecurity
                )
                RowDivider()
                SettingRow(
                    icon = Icons.Filled.DateRange,
                    accent = accentOf("audit"),
                    title = "审计日志",
                    subtitle = "什么时候改过哪条记录",
                    onClick = onOpenAudit
                )
            }

            SectionTitle("关于")
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(accentOf("vaultbook").copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "V",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = accentOf("vaultbook")
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "VaultBook",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "版本 " + BuildConfig.VERSION_NAME,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = if (entryCount < 0) "读取中…" else "共 $entryCount 条",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "数据只存在这台手机里，没有密码要记，打开就能看。"
                            + "代价是：能解锁这台手机、或能读到本应用数据的人，也能看到这些账号。"
                            + "所以别把手机随便借人，重要账号建议开两步验证。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 2.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column { content() }
    }
}

@Composable
private fun RowDivider() {
    androidx.compose.material3.HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        modifier = Modifier.padding(start = 66.dp)
    )
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    accent: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp)
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
