package com.yg.vaultbook.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yg.vaultbook.data.VaultGateway
import com.yg.vaultbook.data.backup.BackupFileInfo
import com.yg.vaultbook.data.backup.BackupFormatException
import com.yg.vaultbook.data.backup.BackupPasswordException
import com.yg.vaultbook.data.BackupReminder
import com.yg.vaultbook.data.backup.BackupService
import com.yg.vaultbook.data.backup.CsvImport
import com.yg.vaultbook.data.backup.CsvParseResult
import com.yg.vaultbook.data.backup.ImportResult
import com.yg.vaultbook.data.backup.VaultSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份页：导出 / 导入加密备份文件。
 *
 * ── 为什么备份密码要单独一个 ────────────────────────────────────
 * 三个理由：①备份可能被放到网盘，不该让它跟主密码有任何关联；
 * ②换主密码不该让旧备份失效；③备份密码泄露不等于主密码泄露。
 *
 * 代价是用户要记两个密码，所以页面上必须把"备份密码丢了就没有任何办法"
 * 这句话写在最显眼的位置 —— 不写清楚，用户一定会当成主密码来输。
 *
 * ── 导入是两步 ────────────────────────────────────────────────
 * 先解密、拿到条目数，再问"要不要覆盖已存在的"。不能一步做完：
 * 覆盖会抹掉本地较新的数据，而用户在看到数字之前根本不知道自己在覆盖什么。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(gateway: VaultGateway, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember(gateway) { gateway.repositoryOrNull() }
    val service = remember(context) { BackupService(context) }
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // 导出
    var showExportDialog by remember { mutableStateOf(false) }
    var exportPwd by remember { mutableStateOf("") }
    var exportPwd2 by remember { mutableStateOf("") }
    var exportSnapshot by remember { mutableStateOf<VaultSnapshot?>(null) }

    // 导入
    var importUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var importInfo by remember { mutableStateOf<BackupFileInfo?>(null) }
    var showImportPasswordDialog by remember { mutableStateOf(false) }
    var importPwd by remember { mutableStateOf("") }
    var pendingSnapshot by remember { mutableStateOf<VaultSnapshot?>(null) }
    var showImportConfirm by remember { mutableStateOf(false) }
    var overwrite by remember { mutableStateOf(false) }

    // 表格导入：解析结果先放进预览，确认了才写库
    var csvPreview by remember { mutableStateOf<CsvParseResult?>(null) }

    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupService.MIME_TYPE)
    ) { uri ->
        val snapshot = exportSnapshot
        exportSnapshot = null
        if (uri == null || snapshot == null) {
            // 用户取消了保存对话框，什么也不用说
            return@rememberLauncherForActivityResult
        }
        val pwd = exportPwd.toCharArray()
        scope.launch {
            busy = true
            error = null
            message = null
            try {
                val bytes = service.exportTo(uri, pwd, snapshot)
                BackupReminder.record(context)
                message = "已导出 ${snapshot.entryCount} 条，共 ${bytes / 1024} KB"
            } catch (e: Exception) {
                error = "导出失败：${e.message ?: "未知错误"}"
            } finally {
                pwd.fill('\u0000')
                snapshot.clearSecrets()
                exportPwd = ""
                exportPwd2 = ""
                busy = false
            }
        }
    }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importUri = uri
        scope.launch {
            busy = true
            error = null
            try {
                // 先只读文件头：选错文件不该让用户先输一遍密码
                val info = service.describe(uri)
                importInfo = info
                showImportPasswordDialog = true
            } catch (e: BackupFormatException) {
                error = e.message
            } catch (e: Exception) {
                error = "读取文件失败：${e.message ?: "未知错误"}"
            } finally {
                busy = false
            }
        }
    }

    // 表格导入走另一条路：选中文件 → 解析 → 预览 → 确认才写库。
    // 解析放 Default 线程，几千行的表不会卡界面。
    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        error = null
        message = null
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("打不开这个文件")
                }
                if (bytes.size > MAX_CSV_BYTES) {
                    error = "文件有 ${bytes.size / 1024} KB，表格导入只处理 8 MB 以内的"
                } else {
                    val parsed = withContext(Dispatchers.Default) { CsvImport.parse(bytes) }
                    if (parsed.rows.isEmpty()) {
                        error = "没读出可导入的行：" + parsed.summary()
                    } else {
                        csvPreview = parsed
                    }
                }
            } catch (e: Exception) {
                error = "读取表格失败：${e.message ?: "未知错误"}"
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("备份", style = MaterialTheme.typography.titleLarge) },
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("备份文件里有什么", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "全部条目（含回收站）、分类、自定义字段。整个文件用你在这里输入的" +
                            "备份密码加密，和主密码无关。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "备份密码丢了，这个文件就再也打不开了 —— 没有找回功能，也没有后门。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                onClick = {
                    error = null
                    message = null
                    exportPwd = ""
                    exportPwd2 = ""
                    showExportDialog = true
                }
            ) {
                Text("导出备份")
            }

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                onClick = {
                    error = null
                    message = null
                    importInfo = null
                    openLauncher.launch(arrayOf("*/*"))
                }
            ) {
                Text("导入备份")
            }

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                onClick = {
                    error = null
                    message = null
                    csvLauncher.launch(arrayOf("*/*"))
                }
            ) {
                Text("从表格导入（CSV）")
            }

            Text(
                "表格可以是 Excel 另存的 CSV、记事本存的文本。认「平台 / 账号 / 密码 / 网址 / 备注 / 分类」这些列名，" +
                    "没有列名就按列顺序读；只有两列时当成「平台 + 密码」。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (busy) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.height(0.dp))
                    Text(
                        "  正在派生密钥（约 1 秒）…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("建议", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "导出后把文件复制一份到别的地方（电脑、U 盘、网盘都行）。" +
                            "放在手机里起不到备份作用 —— 手机丢了文件也一起丢了。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("设置备份密码") },
            text = {
                Column {
                    Text(
                        "这个密码只用来加密备份文件，可以和主密码不同。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = exportPwd,
                        onValueChange = { exportPwd = it },
                        label = { Text("备份密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Next
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = exportPwd2,
                        onValueChange = { exportPwd2 = it },
                        label = { Text("再输一次") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (exportPwd.isNotEmpty() && exportPwd.length < MIN_BACKUP_PASSWORD) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "建议至少 $MIN_BACKUP_PASSWORD 位",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = exportPwd.length >= MIN_BACKUP_PASSWORD && exportPwd == exportPwd2,
                    onClick = {
                        showExportDialog = false
                        busy = true
                        scope.launch {
                            try {
                                // 局部别名：`repo` 是可空字段，闭包里无法依赖早退做智能转换
                                val r = repo ?: error("保险库未解锁")
                                // 先把库读成快照（这一步要解密全部密码），再让用户选保存位置。
                                // 顺序反过来的话，用户在保存对话框里停留很久，
                                // 期间内存里一直躺着一份明文。
                                val snapshot = r.exportSnapshot()
                                exportSnapshot = snapshot
                                val stamp = SimpleDateFormat(
                                    "yyyyMMdd-HHmm",
                                    Locale.getDefault()
                                ).format(Date())
                                createLauncher.launch(
                                    BackupService.FILE_NAME_PREFIX + stamp +
                                        BackupService.FILE_NAME_SUFFIX
                                )
                            } catch (e: Exception) {
                                error = "读取保险库失败：${e.message ?: "未知错误"}"
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) {
                    Text("选择保存位置")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) { Text("取消") }
            }
        )
    }

    if (showImportPasswordDialog) {
        AlertDialog(
            onDismissRequest = { showImportPasswordDialog = false },
            title = { Text("输入这份备份的密码") },
            text = {
                Column {
                    importInfo?.let {
                        Text(
                            it.summary(),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    OutlinedTextField(
                        value = importPwd,
                        onValueChange = { importPwd = it },
                        label = { Text("备份密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = importPwd.isNotEmpty(),
                    onClick = {
                        val uri = importUri ?: return@TextButton
                        showImportPasswordDialog = false
                        val pwd = importPwd.toCharArray()
                        importPwd = ""
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                val snapshot = service.importFrom(uri, pwd)
                                if (snapshot.entryCount == 0) {
                                    snapshot.clearSecrets()
                                    error = "这份备份里没有条目"
                                } else {
                                    pendingSnapshot = snapshot
                                    showImportConfirm = true
                                }
                            } catch (e: BackupPasswordException) {
                                error = e.message
                            } catch (e: BackupFormatException) {
                                error = e.message
                            } catch (e: Exception) {
                                error = "导入失败：${e.message ?: "未知错误"}"
                            } finally {
                                pwd.fill('\u0000')
                                busy = false
                            }
                        }
                    }
                ) {
                    Text("解密")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportPasswordDialog = false }) { Text("取消") }
            }
        )
    }

    val snapshotToImport = pendingSnapshot
    if (showImportConfirm && snapshotToImport != null) {
        AlertDialog(
            onDismissRequest = {
                showImportConfirm = false
                snapshotToImport.clearSecrets()
                pendingSnapshot = null
            },
            title = { Text("备份里有 ${snapshotToImport.entryCount} 条") },
            text = {
                Column {
                    Text(
                        "共 ${snapshotToImport.categories.size} 个分类。" +
                            "默认只补不覆盖：本地已有的条目会原样保留。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("覆盖已存在的条目", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "开着的话，本地改得更新的版本会被备份里的版本盖掉",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Switch(checked = overwrite, onCheckedChange = { overwrite = it })
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportConfirm = false
                        busy = true
                        error = null
                        message = null
                        scope.launch {
                            try {
                                val r = repo ?: error("保险库未解锁")
                                val result: ImportResult =
                                    r.importSnapshot(snapshotToImport, overwrite)
                                message = "导入完成：" + result.summary()
                            } catch (e: Exception) {
                                error = "写入失败：${e.message ?: "未知错误"}"
                            } finally {
                                snapshotToImport.clearSecrets()
                                pendingSnapshot = null
                                busy = false
                            }
                        }
                    }
                ) {
                    Text("开始导入")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showImportConfirm = false
                        snapshotToImport.clearSecrets()
                        pendingSnapshot = null
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }

    val preview = csvPreview
    if (preview != null) {
        AlertDialog(
            onDismissRequest = {
                preview.clearSecrets()
                csvPreview = null
            },
            title = { Text("这张表能读 ${preview.rows.size} 条") },
            text = {
                Column {
                    Text(
                        preview.summary(),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(10.dp))
                    preview.rows.take(4).forEach { row ->
                        Text(
                            "· " + row.title +
                                (row.username?.let { " / $it" } ?: "") +
                                if (row.password != null) "（有密码）" else "（没密码）",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    if (preview.rows.size > 4) {
                        Text("… 其余 ${preview.rows.size - 4} 条", style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "同名的会跳过，不覆盖你现在的内容；分类按表里的名字自动建。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        csvPreview = null
                        val r = repo
                        if (r == null) {
                            error = "保险库未解锁"
                            return@TextButton
                        }
                        busy = true
                        error = null
                        message = null
                        scope.launch {
                            try {
                                val result: ImportResult = r.importCsv(preview.rows)
                                message = "表格导入完成：" + result.summary()
                            } catch (e: Exception) {
                                error = "写入失败：${e.message ?: "未知错误"}"
                            } finally {
                                preview.clearSecrets()
                                busy = false
                            }
                        }
                    }
                ) {
                    Text("开始导入")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        preview.clearSecrets()
                        csvPreview = null
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }
}

/** 备份密码的建议下限。不是硬校验 —— 用户想用短的也行，只是会提示。 */
private const val MIN_BACKUP_PASSWORD = 8

/** 表格导入的上限。表格是纯文本，8 MB 已经是几十万行，再大就不是给人手动导的了。 */
private const val MAX_CSV_BYTES = 8L * 1024 * 1024
