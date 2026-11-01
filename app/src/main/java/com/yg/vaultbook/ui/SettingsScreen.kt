package com.yg.vaultbook.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.yg.vaultbook.BuildConfig
import com.yg.vaultbook.accessibility.CaptureAccessibilityService
import com.yg.vaultbook.autofill.VaultAutofillService
import com.yg.vaultbook.core.util.EnabledServices
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

    // 两个系统服务当前到底开没开。
    //
    // 以前这里什么都不显示，用户在系统里关掉自动填充（换机、清数据、被 ROM
    // 优化掉）之后，应用照样是"能打开、能看、但网页上死活填不进去"，
    // 完全没有线索指回设置。状态直接读系统设置字段，不猜。
    var autofillOn by remember { mutableStateOf(isAutofillEnabled(context)) }
    var accessibilityOn by remember { mutableStateOf(isAccessibilityEnabled(context)) }

    // 从系统设置页返回时 Activity 不会重建，只能靠 ON_RESUME 重新读一次。
    // 不挂观察者的话，用户开完服务回来看到的还是"未开启"，会以为失败了。
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity, context) {
        if (activity == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    autofillOn = isAutofillEnabled(context)
                    accessibilityOn = isAccessibilityEnabled(context)
                }
            }
            activity.lifecycle.addObserver(observer)
            onDispose { activity.lifecycle.removeObserver(observer) }
        }
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
            SectionTitle("系统服务")
            SettingsCard {
                SettingRow(
                    icon = Icons.AutoMirrored.Filled.Send,
                    accent = accentOf("autofill"),
                    title = "自动填充",
                    subtitle = if (autofillOn) {
                        "登录网页和 App 时自动填账号密码"
                    } else {
                        "还没开，现在只能在应用里手动复制"
                    },
                    status = autofillOn,
                    onClick = { openAutofillSettings(context) }
                )
                RowDivider()
                SettingRow(
                    icon = Icons.Filled.Person,
                    accent = accentOf("a11y"),
                    title = "无障碍识别",
                    subtitle = if (accessibilityOn) {
                        "能认出那些不让自动填充的登录页"
                    } else {
                        "还没开，有些 App 的登录页填不进去"
                    },
                    status = accessibilityOn,
                    onClick = { openAccessibilitySettings(context) }
                )
            }

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
    onClick: () -> Unit,
    /** 传了就在箭头前面加一个"已开启 / 未开启"胶囊；不传（null）就是普通设置项。 */
    status: Boolean? = null
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
        if (status != null) {
            StatusPill(on = status)
            Spacer(Modifier.width(6.dp))
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * 「已开启 / 未开启」小胶囊。
 *
 * 未开启不用红色：它不是错误，是"还没配"，红色会让人以为应用坏了。
 */
@Composable
private fun StatusPill(on: Boolean) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = if (on) "已开启" else "未开启",
        style = MaterialTheme.typography.labelSmall,
        color = if (on) colors.primary else colors.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (on) colors.primary.copy(alpha = 0.12f)
                else colors.surfaceVariant.copy(alpha = 0.70f)
            )
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

/* ------------------------------------------------------------------ *
 * 系统服务开关的读取与跳转。
 *
 * 这些都是"读一个系统设置字段 / 拉起一个系统页面"，没有可展示的业务，
 * 所以放在文件末尾而不是单独建类。字符串解析抽到了 EnabledServices，
 * 那边有单测（各 ROM 写法不一，只能靠用例兜住）。
 * ------------------------------------------------------------------ */

/** 系统当前生效的自动填充服务是不是本应用。 */
private fun isAutofillEnabled(context: Context): Boolean {
    val raw = runCatching {
        Settings.Secure.getString(context.contentResolver, "autofill_service")
    }.getOrNull()
    return EnabledServices.contains(
        raw = raw,
        packageName = context.packageName,
        className = VaultAutofillService::class.java.name
    )
}

/** 系统的无障碍服务列表里有没有本应用。 */
private fun isAccessibilityEnabled(context: Context): Boolean {
    val raw = runCatching {
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
    }.getOrNull()
    return EnabledServices.contains(
        raw = raw,
        packageName = context.packageName,
        className = CaptureAccessibilityService::class.java.name
    )
}

/**
 * 直接把用户送到"选自动填充服务"的界面。
 *
 * `ACTION_REQUEST_SET_AUTOFILL_SERVICE` 在部分 ROM 上没实现（抛
 * ActivityNotFoundException），退回系统设置首页 —— 找不到总比点了没反应强。
 */
private fun openAutofillSettings(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
        .setData(Uri.parse("package:" + context.packageName))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val opened = runCatching { context.startActivity(direct) }.isSuccess
    if (!opened) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

private fun openAccessibilitySettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** 从任意 Context 往上找到承载 Compose 的 Activity（用来听生命周期）。 */
private fun Context.findActivity(): ComponentActivity? {
    var current: Context? = this
    while (current != null) {
        if (current is ComponentActivity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
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
