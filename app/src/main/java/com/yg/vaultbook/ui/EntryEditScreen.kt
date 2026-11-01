package com.yg.vaultbook.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.yg.vaultbook.core.util.CredentialParser
import com.yg.vaultbook.core.util.PasswordStrength
import com.yg.vaultbook.core.util.Totp
import com.yg.vaultbook.data.VaultGateway
import com.yg.vaultbook.data.entity.AuditLogEntity
import com.yg.vaultbook.domain.CustomFieldDraft
import com.yg.vaultbook.domain.EntryDraft
import com.yg.vaultbook.domain.PlainCategory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * 新建 / 编辑条目。
 * 一条重要的取舍：**编辑时不预填已有密码**。
 * 打开编辑页就把密码解密进内存并显示在输入框里，等于每次查看条目都强制解密一次，
 * 而绝大多数编辑根本不碰密码。所以这里的密码框在编辑模式下是空的，
 * 提示语明确写"留空表示不修改"。
 *
 * 想看到原密码，用密码框下方的「查看当前密码」—— 那是用户主动的一次解密动作，
 * 点「隐藏」或离开页面就立刻把字符数组清零。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EntryEditScreen(
    gateway: VaultGateway,
    entryId: String?,
    onDone: () -> Unit,
    /**
     * 删除成功后去哪。
     *
     * 不能复用 [onDone]：从详情页进来的编辑，保存后要回详情页（能看到改动），
     * 但删除后详情页已经不存在 —— 必须回列表。为 null 时退回 [onDone]，
     * 保持旧的调用方不用改。
     */
    onDeleted: (() -> Unit)? = null,
    /**
     * 从别的应用「分享」进来的文本（`ACTION_SEND` / `text/plain`）。
     *
     * 非 null 时页面打开就会尝试识别并预填。这是比"复制 → 切回应用 → 点粘贴"
     * 少两步的路径，也是唯一**不依赖读剪贴板**的入口 —— 部分国产 ROM 会
     * 禁止应用读剪贴板，那条路会静默失效。
     */
    sharedText: String? = null,
    /** 分享文本处理完（无论认没认出来）后回调，让上层清掉它，避免重进页面时重复预填。 */
    onSharedTextConsumed: () -> Unit = {}
) {
    val repo = remember { gateway.repositoryOrNull() }

    if (repo == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("保险库未解锁", color = MaterialTheme.colorScheme.error)
        }
        return
    }

    val isEdit = entryId != null

    var loading by remember { mutableStateOf(isEdit) }
    var title by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var totp by remember { mutableStateOf("") }
    var categoryId by remember { mutableStateOf<String?>(null) }

    /**
     * 收藏标记。
     *
     * 早先这里是硬编码 `favorite = false` 提交，等于**用户每次改个错别字
     * 都会把收藏状态清掉**。现在改成：编辑时把原值读进来，保存时原样带回去，
     * 并且在分类 chip 后面加一个「收藏」开关，让这个字段第一次真正可用。
     */
    var favorite by remember { mutableStateOf(false) }

    /**
     * 自定义字段。编辑已有条目时**保留每行的 `id`** ——
     * 字段密文的 AAD 绑的是 `字段id|cf_label`，如果在这里换新 id，
     * 旧密文就再也解不开了（会报完整性失败）。新建行的 id 是 null，
     * 由仓储层生成。
     */
    var customFields by remember { mutableStateOf<List<CustomFieldDraft>>(emptyList()) }

    var categories by remember { mutableStateOf<List<PlainCategory>>(emptyList()) }
    var revealed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    /**
     * 已有密码的**按需**解密结果。null 表示"没看过 / 已隐藏"。
     *
     * 只有用户主动点「查看当前密码」才会解密一次；显示区被收起、
     * 页面被销毁、或换了个字符数组时，旧的那份立刻 `fill('\u0000')`。
     * 这就是"密码不当常驻内存"落地的方式。
     */
    var existingPwd by remember { mutableStateOf<CharArray?>(null) }
    val context = LocalContext.current

    /**
     * 从剪贴板识别出的候选（还没填入）。
     *
     * 非 null 时页面顶部会出现一张提示卡，用户点「填入」才真正写进输入框。
     * **不做自动填入** —— 剪贴板里可能躺着一条与本次操作无关的账号密码，
     * 默默替换用户正在输入的内容比不做要糟。
     */
    var pasted by remember { mutableStateOf<CredentialParser.Result?>(null) }

    /** 剪贴板里认出了好几组时的候选（点"看看"才进批量确认框）。 */
    var clipboardBatch by remember { mutableStateOf<List<CredentialParser.Result>>(emptyList()) }

    /** 批量确认框里正在看的那几组；非 null 即对话框打开。 */
    var batchRows by remember { mutableStateOf<List<CredentialParser.Result>?>(null) }

    /**
     * 手动粘贴对话框。
     *
     * 存在的唯一理由：**不读剪贴板**。部分国产 ROM（以及 Android 10+ 在
     * 某些后台场景）会拒绝 `getPrimaryClip()`，那时「从剪贴板粘贴」按钮点了
     * 只会弹"剪贴板是空的"，用户无从下手。这里给一个普通多行输入框 ——
     * 用户在框里长按选「粘贴」，走的是系统自己的粘贴菜单，一定可用。
     */
    var manualPasteOpen by remember { mutableStateOf(false) }
    var manualPasteText by remember { mutableStateOf("") }
    var manualPasteError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(existingPwd) {
        val snapshot = existingPwd
        onDispose { snapshot?.fill('\u0000') }
    }

    val scope = rememberCoroutineScope()

    /**
     * 读剪贴板里的第一段文本。
     *
     * Android 10+ 只有前台应用能读剪贴板，编辑页必然在前台，所以这里不需要
     * 额外判断；读不到（剪贴板空、被系统限制、内容不是文本）就返回 null，
     * 静默跳过 —— 这件事本来就不该影响正常输入。
     */
    fun readClipboard(): String? = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val item = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        item?.coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    /**
     * 把识别结果写进输入框。
     *
     * 只覆盖"识别到了"的字段，其余保留用户已经输入的内容 —— 用户可能先手动
     * 填了标题，再从剪贴板补账号密码。
     *
     * 密码填进来后强制亮出来：用户刚粘贴，下一步就是核对它对不对，
     * 这时候还打码等于让他多点一次「显示」。
     */
    fun applyParsed(parsed: CredentialParser.Result) {
        parsed.username?.let { username = it }
        parsed.password?.let {
            password = it
            revealed = true
        }
        parsed.url?.let { url = it }
        if (title.isBlank()) {
            title = parsed.title ?: parsed.url ?: ""
        }
        pasted = null
        error = null
    }

    /**
     * 识别一段文本并填入。
     *
     * @return 是否成功（"成功"= 至少认出了账号或密码，只认出网址不算）
     *
     * 不在这里改 [error]：三条入口（剪贴板 / 手动粘贴 / 外部分享）的提示文案不同，
     * 由调用方各自决定说什么。
     */
    fun parseAndApply(raw: String?): Boolean {
        val many = CredentialParser.parseMany(raw)
        if (many.isEmpty()) return false
        // 一组：照旧填进表单；多组：交给批量确认框，别默默只填第一条
        // —— 用户抄了十条进来，只填一条等于把其余九条丢了。
        if (many.size == 1) {
            applyParsed(many[0])
            return true
        }
        pasted = null
        batchRows = many
        return true
    }

    /**
     * 批量落库。
     *
     * @param onlyTitlePassword 勾上后丢掉账号，只存「标题 + 密码」。用户的原话是
     *   "像我一般存都是哪个平台+密码这样保存" —— 账号是选填，不是必填。
     */
    fun saveBatch(rows: List<CredentialParser.Result>, onlyTitlePassword: Boolean) {
        if (busy || rows.isEmpty()) return
        busy = true
        error = null
        scope.launch {
            var saved = 0
            try {
                for (row in rows) {
                    val draft = EntryDraft(
                        title = (row.title ?: row.url ?: row.username ?: "").trim(),
                        username = if (onlyTitlePassword) null else row.username,
                        password = row.password?.toCharArray(),
                        url = row.url,
                        notes = null,
                        totp = null,
                        categoryId = null,
                        favorite = false,
                        customFields = emptyList()
                    )
                    try {
                        repo.createEntry(draft)
                        saved++
                    } finally {
                        draft.clearSecrets()
                    }
                }
                batchRows = null
                clipboardBatch = emptyList()
                onDone()
            } catch (e: Exception) {
                error = "连第 ${saved + 1} 条就停了：${e.message ?: "写入失败"}" +
                    if (saved > 0) "（前面 $saved 条已经存好）" else ""
            } finally {
                busy = false
            }
        }
    }

    /**
     * 读剪贴板并识别。
     *
     * @return true 表示已经填上了；false 表示没读到或没认出。
     *   不在这里写 [error]，交给调用方决定说什么 —— 同一个动作，
     *   「粘贴框」和「手动粘贴框」要给的提示不一样。
     */
    fun pasteFromClipboard(): Boolean {
        val text = readClipboard() ?: return false
        return parseAndApply(text)
    }

    /**
     * 粘贴框的唯一动作：先读剪贴板，不行就直接把手动粘贴框推出来。
     *
     * 用户点的是一整块写着「点这里粘贴」的区域，他不该关心剪贴板能不能读。
     * 所以这里必须一次点到位：能读就直接填上，读不到就立刻打开手动粘贴框，
     * 而不是先弹一句"剪贴板是空的"让他自己去找下一个按钮 ——
     * 那一句提示在最需要它的时候（ROM 禁止读剪贴板）等于死路。
     */
    fun smartPaste() {
        if (pasteFromClipboard()) return
        manualPasteText = ""
        manualPasteError = null
        manualPasteOpen = true
    }

    /** 手动粘贴对话框的「识别」按钮。 */
    fun submitManualPaste() {
        if (manualPasteText.isBlank()) {
            manualPasteError = "先把文字粘贴进来"
            return
        }
        if (parseAndApply(manualPasteText)) {
            manualPasteOpen = false
            manualPasteText = ""
            manualPasteError = null
            error = null
        } else {
            manualPasteError = "没从这段文字里认出账号或密码"
        }
    }

    // 从别的应用分享进来：直接识别并填入。
    // 分享是明确意图（用户主动选了"发到VaultBook"），所以不像剪贴板那样只给提示卡。
    LaunchedEffect(sharedText) {
        val raw = sharedText ?: return@LaunchedEffect
        if (!parseAndApply(raw)) {
            error = "没从分享内容里认出账号或密码，手动填一下"
        }
        onSharedTextConsumed()
    }

    LaunchedEffect(entryId) {
        categories = repo.categories()
        if (entryId != null) {
            val detail = repo.loadDetail(entryId)
            if (detail != null) {
                title = detail.summary.title
                username = detail.summary.username.orEmpty()
                url = detail.summary.url.orEmpty()
                notes = detail.notes.orEmpty()
                totp = detail.totp.orEmpty()
                categoryId = detail.summary.categoryId
                favorite = detail.summary.favorite
                customFields = detail.customFields.map { f ->
                    CustomFieldDraft(
                        id = f.id,
                        label = f.label,
                        value = f.value,
                        type = f.type
                    )
                }
            }
            loading = false
        }
    }

    // 新建页进场时看一眼剪贴板，认出账号密码就在顶部提示一下（不自动填）。
    // 只在还什么都没填时提示：用户从别处复制了东西、回来接着改旧条目时，
    // 不该被莫名打扰。
    LaunchedEffect(entryId) {
        if (isEdit) return@LaunchedEffect
        if (title.isNotBlank() || username.isNotBlank() ||
            password.isNotBlank() || url.isNotBlank()
        ) {
            return@LaunchedEffect
        }
        val many = CredentialParser.parseMany(readClipboard())
        when {
            many.size > 1 -> clipboardBatch = many
            many.size == 1 && many[0].hasCredential -> pasted = many[0]
        }
    }

    val strengthScore = remember(password) {
        if (password.isEmpty()) -1 else PasswordStrength.score(password.toCharArray())
    }

    fun commit() {
        if (busy) return
        error = null
        if (title.isBlank()) {
            error = "标题不能为空"
            return
        }
        busy = true
        scope.launch {
            val draft = EntryDraft(
                title = title.trim(),
                username = username.trim().takeIf { it.isNotEmpty() },
                password = password.takeIf { it.isNotEmpty() }?.toCharArray(),
                url = url.trim().takeIf { it.isNotEmpty() },
                notes = notes.takeIf { it.isNotBlank() },
                totp = totp.trim().takeIf { it.isNotBlank() },
                categoryId = categoryId,
                favorite = favorite,
                // 空行不提交：用户点了「添加」又没填任何东西，不该在库里留一条空字段
                customFields = customFields.filter {
                    it.label.isNotBlank() || it.value.isNotBlank()
                }
            )
            try {
                if (entryId == null) repo.createEntry(draft) else repo.updateEntry(entryId, draft)
                onDone()
            } catch (e: Exception) {
                error = e.message ?: "保存失败"
            } finally {
                draft.clearSecrets()
                busy = false
            }
        }
    }

    fun remove() {
        if (busy || entryId == null) return
        busy = true
        scope.launch {
            try {
                repo.trashEntry(entryId)
                if (onDeleted != null) onDeleted() else onDone()
            } catch (e: Exception) {
                error = e.message ?: "删除失败"
            } finally {
                busy = false
            }
        }
    }

    fun toggleExistingPassword() {
        if (existingPwd != null) {
            existingPwd = null       // DisposableEffect 会清
            return
        }
        if (entryId == null) return
        scope.launch {
            try {
                existingPwd = repo.revealPassword(entryId)
            } catch (e: Exception) {
                error = "无法解密当前密码：${e.message}"
            }
        }
    }

    fun copyExistingPassword() {
        val pwd = existingPwd ?: return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = String(pwd)
        cm.setPrimaryClip(ClipData.newPlainText("VaultBook", text))
        scope.launch {
            runCatching { repo.audit(AuditLogEntity.EVENT_COPY, entryId, "password") }
            delay(CLIPBOARD_CLEAR_MS)
            // 只在剪贴板里还是我们写进去的那串时才清空 ——
            // 否则会清掉用户后来复制的别的内容。
            val current = cm.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.text
                ?.toString()
            if (current == text) {
                cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }

    Scaffold(
        topBar = {
            VaultTopBar(
                title = if (isEdit) "编辑条目" else "新建条目",
                subtitle = "账号可以空着，只存平台和密码也行",
                onBack = onDone,
                backIcon = Icons.Filled.Close,
                backLabel = "关闭",
                actions = {
                    TextButton(onClick = { commit() }, enabled = !busy && !loading) {
                        Text("保存", style = MaterialTheme.typography.labelLarge)
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->

        if (loading) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // 粘贴区：一整块可点的卡片，而不是两个小文字按钮。
            // 用户的原话是"留个框，点击即可手动粘贴或者从剪贴板粘贴"——
            // 框比按钮更明确地表达了"这里是放东西的地方"。
            if (!isEdit) {
                PasteBox(enabled = !busy, onClick = { smartPaste() })
                Spacer(Modifier.height(14.dp))
            }

            pasted?.let { candidate ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            text = "剪贴板里有这些",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = describeCandidate(candidate),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { pasted = null }) { Text("忽略") }
                            TextButton(onClick = { applyParsed(candidate) }) { Text("填入") }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // 剪贴板里抄着好几组的时候，先给一张"有 N 组"的卡，而不是偷偷只填第一条。
            if (clipboardBatch.size > 1) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            text = "剪贴板里有 ${clipboardBatch.size} 组账号密码",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "可以一次全存进去，也可以只挑几条。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { clipboardBatch = emptyList() }) { Text("忽略") }
                            TextButton(onClick = { batchRows = clipboardBatch }) { Text("看看") }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it; error = null },
                label = { Text("标题") },
                placeholder = { Text("例如：淘宝") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                // 账号是可选的：很多站只记得住"平台 + 密码"，
                // 逼着先编一个账号填进去才是添麻烦。
                label = { Text("账号（可选）") },
                placeholder = { Text("手机号 / 邮箱 / 用户名，空着也行") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                placeholder = {
                    Text(
                        if (isEdit) "留空表示不修改"
                        else "可手动输入，或点右侧生成"
                    )
                },
                singleLine = true,
                enabled = !busy,
                visualTransformation = if (revealed) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    Row {
                        TextButton(onClick = { revealed = !revealed }) {
                            Text(if (revealed) "隐藏" else "显示")
                        }
                        TextButton(onClick = {
                            password = generatePassword()
                            revealed = true
                        }) {
                            Text("生成")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            if (strengthScore >= 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "强度：${PasswordStrength.labelOf(strengthScore)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (strengthScore <= 1) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 已有密码的查看入口。刻意与"输入新密码"分开：
            // 上面那个框永远只表示"想改成什么"，这里才是"现在是什么"。
            if (isEdit) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { toggleExistingPassword() }, enabled = !busy) {
                        Text(if (existingPwd == null) "查看当前密码" else "隐藏")
                    }
                    if (existingPwd != null) {
                        TextButton(onClick = { copyExistingPassword() }, enabled = !busy) {
                            Text("复制")
                        }
                        Text(
                            text = "复制后 30 秒自动清空剪贴板",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                existingPwd?.let { pwd ->
                    Text(
                        text = String(pwd),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("网址") },
                placeholder = { Text("https://…（用于自动填充匹配）") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            // 两因素密钥。允许直接粘 otpauth:// URI ——
            // 网站上点「无法扫描」复制出来的就是那一整串，让用户自己去拆 secret 参数是不现实的。
            OutlinedTextField(
                value = totp,
                onValueChange = { totp = it },
                label = { Text("动态码密钥（可选）") },
                placeholder = { Text("Base32 密钥，或整段 otpauth://totp/…") },
                enabled = !busy,
                minLines = 2,
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            if (totp.isNotBlank()) {
                // 只取"是否有效 + 周期"，拿到后立刻把解码出的密钥清零：
                // 这里的目的只是即时反馈，没必要在 UI 层多留一份密钥。
                val totpPeriod = remember(totp) {
                    Totp.parseConfig(totp)?.let { cfg ->
                        val period = cfg.periodSeconds
                        cfg.secret.fill(0)
                        period
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (totpPeriod != null) {
                        "✓ 已识别，保存后详情页会显示每 $totpPeriod 秒刷新的验证码"
                    } else {
                        "✗ 没认出这是有效的 TOTP 密钥，保存后不会显示验证码"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (totpPeriod != null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("备注") },
                enabled = !busy,
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )

            if (categories.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "分类",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                // FlowRow 而不是 Row：分类多起来（用户自己建了七八个）时
                // 单行 Row 会把最后几个挤出屏幕边缘，且没有任何换行提示。
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categories.forEach { c ->
                        FilterChip(
                            selected = categoryId == c.id,
                            onClick = {
                                categoryId = if (categoryId == c.id) null else c.id
                            },
                            label = { Text(c.name) },
                            enabled = !busy
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            // 收藏与分类无关，放在 if (categories.isNotEmpty()) 之外，
            // 万一分类型读取失败，收藏开关仍然可用。
            FilterChip(
                selected = favorite,
                onClick = { favorite = !favorite },
                label = { Text(if (favorite) "★ 已收藏" else "☆ 收藏") },
                enabled = !busy
            )

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "自定义字段",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = {
                        customFields = customFields + CustomFieldDraft(
                            id = null,
                            label = "",
                            value = "",
                            type = "text"
                        )
                    },
                    enabled = !busy
                ) { Text("添加一项") }
            }

            if (customFields.isEmpty()) {
                Text(
                    text = "用来放网站没要求、但你不想忘的东西：交易密码、密保问题答案、柜员编号…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            customFields.forEachIndexed { index, field ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = field.label,
                                onValueChange = { value ->
                                    customFields = customFields.toMutableList().also {
                                        it[index] = it[index].copy(label = value)
                                    }
                                },
                                label = { Text("名称") },
                                singleLine = true,
                                enabled = !busy,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    customFields = customFields.filterIndexed { i, _ -> i != index }
                                },
                                enabled = !busy
                            ) {
                                Text("移除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = field.value,
                            onValueChange = { value ->
                                customFields = customFields.toMutableList().also {
                                    it[index] = it[index].copy(value = value)
                                }
                            },
                            label = { Text("内容") },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = field.type == "text",
                                onClick = {
                                    customFields = customFields.toMutableList().also {
                                        it[index] = it[index].copy(type = "text")
                                    }
                                },
                                label = { Text("普通文本") },
                                enabled = !busy
                            )
                            FilterChip(
                                selected = field.type == "secret",
                                onClick = {
                                    customFields = customFields.toMutableList().also {
                                        it[index] = it[index].copy(type = "secret")
                                    }
                                },
                                label = { Text("密文（列表打码）") },
                                enabled = !busy
                            )
                        }
                    }
                }
            }

            error?.let {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { commit() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("保存")
                }
            }

            if (isEdit) {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { remove() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("删除（进回收站，30 天后彻底清除）", color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(48.dp))
        }
    }

    if (manualPasteOpen) {
        AlertDialog(
            onDismissRequest = { manualPasteOpen = false },
            title = { Text("粘贴文字并识别") },
            text = {
                Column {
                    Text(
                        text = "在下面的框里长按 → 粘贴。这条路不读剪贴板，任何系统都能用。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = manualPasteText,
                        onValueChange = { manualPasteText = it; manualPasteError = null },
                        label = { Text("粘贴到这里") },
                        placeholder = { Text("例如：账号：admin 密码：abc123") },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                    manualPasteError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { submitManualPaste() }) { Text("识别") }
            },
            dismissButton = {
                TextButton(onClick = { manualPasteOpen = false }) { Text("取消") }
            }
        )
    }

    batchRows?.let { rows ->
        BatchSaveDialog(
            rows = rows,
            busy = busy,
            onDismiss = { batchRows = null },
            onConfirm = { picked, onlyTitlePassword -> saveBatch(picked, onlyTitlePassword) }
        )
    }
}

/**
 * 一次存多条的确认框。
 *
 * 每行默认勾上，但**不默认保存** —— 认错一条就是往库里塞了一条垃圾，
 * 用户还得自己去删。勾选框让他三秒内能剔掉认错的那几条。
 *
 * "只存标题和密码"默认关着：多数时候账号是有用的，但用户明说过
 * "我一般就存平台加密码"，所以给他一个开关，勾一次就在本次批量里生效。
 */
@Composable
private fun BatchSaveDialog(
    rows: List<CredentialParser.Result>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (List<CredentialParser.Result>, Boolean) -> Unit
) {
    var checked by remember(rows) { mutableStateOf(rows.map { true }) }
    var onlyTitlePassword by remember(rows) { mutableStateOf(false) }
    val picked = rows.filterIndexed { index, _ -> checked[index] }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("识别到 ${rows.size} 组") },
        text = {
            Column {
                Text(
                    text = "勾掉认错的，剩下的会一次存进去。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    rows.forEachIndexed { index, row ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = checked[index],
                                onCheckedChange = { on ->
                                    checked = checked.toMutableList().also { it[index] = on }
                                },
                                enabled = !busy
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = row.title ?: row.url ?: row.username ?: "未命名",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = buildString {
                                        val user = row.username
                                        append(if (user.isNullOrBlank()) "只有密码" else user)
                                        if (!row.password.isNullOrEmpty()) append(" · 已认出密码")
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = onlyTitlePassword,
                        onCheckedChange = { onlyTitlePassword = it },
                        enabled = !busy
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("只存标题和密码", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "不要账号，适合只记得住平台名的时候",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(picked, onlyTitlePassword) },
                enabled = !busy && picked.isNotEmpty()
            ) {
                Text(if (busy) "保存中…" else "存 ${picked.size} 条")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        }
    )
}

/** 剪贴板里密码的存活时长，与 docs/01 的业务阈值表一致（30 秒）。 */
private const val CLIPBOARD_CLEAR_MS: Long = 30_000L

/**
 * 把识别结果拼成给人看的几行。
 *
 * 密码只显示点数，不显示原文 —— 这张卡出现在屏幕上时用户可能正在投屏或
 * 有旁人看着，而"识别对不对"只看账号和网址就能判断个大概。
 */
private fun describeCandidate(candidate: CredentialParser.Result): String = buildList {
    candidate.title?.let { add("标题  $it") }
    candidate.username?.let { add("账号  $it") }
    candidate.password?.let { pwd ->
        add("密码  " + "•".repeat(pwd.length.coerceIn(6, 14)))
    }
    candidate.url?.let { add("网址  $it") }
}.joinToString("\n")

/**
 * 生成随机密码。
 *
 * 字符集**故意去掉易混淆的** `l I 1 O 0`：这密码经常要人眼读、手抄、念给别人，
 * 但去字符会降低字符池，所以长度补偿到 20 位 —— 实际熵仍在 100 bit 以上。
 */
private fun generatePassword(length: Int = 20): String {
    val lower = "abcdefghijkmnopqrstuvwxyz"
    val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    val digits = "23456789"
    val symbols = "!@#%^&*-_=+?"

    val all = lower + upper + digits + symbols
    val random = SecureRandom()
    val out = StringBuilder(length)

    // 先保证四类各至少一个，避免"随机出来一个纯小写"这种弱化情况
    out.append(lower[random.nextInt(lower.length)])
    out.append(upper[random.nextInt(upper.length)])
    out.append(digits[random.nextInt(digits.length)])
    out.append(symbols[random.nextInt(symbols.length)])

    while (out.length < length) {
        out.append(all[random.nextInt(all.length)])
    }

    // 洗牌，让前四位不是固定顺序
    val chars = out.toString().toCharArray()
    for (i in chars.indices.reversed()) {
        val j = random.nextInt(i + 1)
        val tmp = chars[i]
        chars[i] = chars[j]
        chars[j] = tmp
    }
    return String(chars)
}

/**
 * 新建页顶部的粘贴区。
 *
 * 做成一块"放东西的框"而不是两个文字按钮，理由是用户的原话就是"留个框"：
 * 框有面积、有底色、整块可点，一眼能看出"东西可以往这里放"；
 * 而两个 14sp 的 TextButton 挤在右上角，用久了根本想不起来它们存在。
 *
 * 点击行为由 [smartPaste] 决定：能读剪贴板就直接填，读不到就把手动粘贴框推出来。
 */
@Composable
private fun PasteBox(enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        interactionSource = interaction,
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction, 0.98f)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PasteGlyph(
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "点这里粘贴",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "自动认出账号和密码 · 读不到剪贴板会打开手动粘贴框",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                )
            }
        }
    }
}

/** 手画的剪贴板图标 —— core 图标集里没有 ContentPaste，为它引整个 extended 包不值得。 */
@Composable
private fun PasteGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.085f
        // 板身
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.16f, h * 0.17f),
            size = Size(w * 0.68f, h * 0.79f),
            cornerRadius = CornerRadius(w * 0.13f),
            style = Stroke(width = stroke)
        )
        // 顶部夹子
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.34f, h * 0.03f),
            size = Size(w * 0.32f, h * 0.23f),
            cornerRadius = CornerRadius(w * 0.07f),
            style = Stroke(width = stroke)
        )
    }
}
