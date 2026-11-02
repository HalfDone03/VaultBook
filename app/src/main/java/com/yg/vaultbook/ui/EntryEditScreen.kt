package com.yg.vaultbook.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.yg.vaultbook.core.util.CredentialParser
import com.yg.vaultbook.core.util.EntrySignature
import com.yg.vaultbook.core.util.Normalize
import com.yg.vaultbook.core.util.PasswordStrength
import com.yg.vaultbook.core.util.Pinyin
import com.yg.vaultbook.core.util.Totp
import com.yg.vaultbook.data.VaultGateway
import com.yg.vaultbook.data.entity.AuditLogEntity
import com.yg.vaultbook.domain.CustomFieldDraft
import com.yg.vaultbook.domain.EntryDraft
import com.yg.vaultbook.domain.PlainCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
     * 顶部粘贴框里的原文。
     *
     * 用户的原话：「顶部的'点击这里粘贴'应该设为一个文字框，而不应该是点击后
     * 就自动获取了」。所以这里就是一个普通的多行输入框 —— 想贴什么、贴多长都行，
     * 应用不再自己伸手去读剪贴板（除非用户点「读剪贴板」那一下）。
     *
     * 用 remember 而不是 rememberSaveable：这段文字里可能有明文密码，
     * 不该被写进进程重建时保存的状态。
     */
    var pasteText by remember { mutableStateOf("") }

    /**
     * 从外部（剪贴板）往文本框里灌了一次就加一，用来强制重建输入框。
     *
     * 有用户反馈"点读剪贴板后框里没字，但下面写着认出两组"。这是 Compose
     * 输入框的老毛病：`value` 从外部一次性替换、且当时没有焦点时，内部的
     * 选区状态可能停在旧位置，视觉上就像没灌进去。换 key 重造最省事，
     * 也不影响手打 —— 手打时不加这个数。
     */
    var pasteToken by remember { mutableStateOf(0) }

    /** 批量确认框里正在看的那几组；非 null 即对话框打开。 */
    var batchRows by remember { mutableStateOf<List<CredentialParser.Result>?>(null) }

    // 分类弹层。分类数量上来以后，把整排 chip 铺在表单里等于用一个次要字段
    // 挤占主表单的高度，所以改成按需展开。
    var categoryPickerOpen by remember { mutableStateOf(false) }

    /**
     * 进来时那一刻的字段快照。
     *
     * 用途只有一个：点关闭 / 按返回键时判断"到底改没改"。
     * 没改就直接退出，改了就问一句 —— 手滑点到返回就丢掉刚写的密码，
     * 是录入页最容易被骂的一种失败。
     */
    var baseline by remember { mutableStateOf<String?>(null) }

    /** 非 null 即"要丢掉这些改动吗"的确认框打开了。 */
    var confirmDiscard by remember { mutableStateOf(false) }

    /**
     * 粘贴框里的识别结果，随输入实时更新。
     *
     * 空文本框不跑解析；有内容时挪到后台线程 —— 用户可能一次贴进来几十行，
     * 不该卡在绘制帧里。
     */
    val pasteRows by produceState(emptyList<CredentialParser.Result>(), pasteText) {
        value = if (pasteText.isBlank()) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) { CredentialParser.parseMany(pasteText) }
        }
    }

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
        // 备注带着走。此前识别器把整行「备注：备用密码在邮箱里」当字段名丢掉，
        // 用户在粘贴框里根本看不到它，现在补到表单上。
        parsed.notes?.let { if (notes.isBlank()) notes = it }
        if (title.isBlank()) {
            title = parsed.title ?: parsed.url ?: ""
        }
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
            var skipped = 0
            try {
                // 库里已有的「标题 + 账号」签名。批量粘贴同一段文本两遍、
                // 或者一段里本来就写了重复的两行，都会在这里被拦下 ——
                // 以前是照存，结果列表里并排两条一模一样的记录。
                val seen = runCatching { repo.loadSummaries() }
                    .getOrDefault(emptyList())
                    .mapTo(mutableSetOf()) { EntrySignature.of(it.title, it.username) }
                for (row in rows) {
                    val title = (row.title ?: row.url ?: row.username ?: "").trim()
                    val user = if (onlyTitlePassword) null else row.username
                    val key = EntrySignature.of(title, user)
                    if (key in seen) {
                        skipped++
                        continue
                    }
                    val draft = EntryDraft(
                        title = title,
                        username = user,
                        password = row.password?.toCharArray(),
                        url = row.url,
                        notes = row.notes,
                        totp = null,
                        categoryId = null,
                        favorite = false,
                        customFields = emptyList()
                    )
                    try {
                        repo.createEntry(draft)
                        seen += key
                        saved++
                    } finally {
                        draft.clearSecrets()
                    }
                }
                batchRows = null
                pasteText = ""
                if (skipped > 0) {
                    val msg = when {
                        saved == 0 -> "这 $skipped 条库里都已经有了"
                        else -> "存了 $saved 条，跳过 $skipped 条重复的"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
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
     * 顶部「读剪贴板」按钮：用户点这一下才读，读完原样放进文本框。
     *
     * 读不到就明说，不再像早先那样"点一下自动读、读不到又悄悄弹另一个框" ——
     * 那种设计让用户以为框本身会自己抓剪贴板。
     */
    fun readClipboardIntoField() {
        val text = readClipboard()
        if (text == null) {
            Toast.makeText(context, "剪贴板里没有文字", Toast.LENGTH_SHORT).show()
            return
        }
        pasteText = text
        pasteToken++
    }

    /** 把认出的一条写进表单，然后清空粘贴框 —— 内容已经进表单了，留着只会碍事。 */
    fun fillFromPaste(row: CredentialParser.Result) {
        applyParsed(row)
        pasteText = ""
    }

    /**
     * 当前表单内容的一把"指纹"。
     *
     * 用拼接字符串而不是重写 equals：字段就这些，逐项比较要写十行，
     * 拼起来一行就够，且任何一处改动都会让指纹变。
     * 分隔符用不可能出现在输入里的控制字符，避免"标题写 a、账号写 b"
     * 和"标题写 ab"撞成同一个指纹。
     */
    fun signature(): String = buildString {
        append(title).append('\u0002')
        append(username).append('\u0002')
        append(password).append('\u0002')
        append(url).append('\u0002')
        append(notes).append('\u0002')
        append(totp).append('\u0002')
        append(categoryId ?: "").append('\u0002')
        append(favorite).append('\u0002')
        customFields.forEach {
            append(it.id ?: "").append('\u0001')
            append(it.label).append('\u0001')
            append(it.value).append('\u0001')
            append(it.type).append('\u0002')
        }
    }

    fun dirty(): Boolean = baseline?.let { it != signature() } ?: false

    /**
     * 关闭前的总闸。
     *
     * 三条退出路径（左上关闭、系统返回键、保存成功）都走这里，
     * 但它们的行为不一样：保存成功是"事情办完了"，不该再问一遍；
     * 所以保存处仍直接调 [onDone]，只有"用户主动退出"才拦。
     */
    fun requestClose() {
        if (busy || !dirty()) onDone() else confirmDiscard = true
    }

    // 从别的应用分享进来：直接识别并填入。
    // 分享是明确意图（用户主动选了发到VaultBook），所以不像剪贴板那样只给提示卡。
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
        // 记下"刚进来长什么样"。新建页也记 —— 用户可能只是点进来看看，
        // 什么都没写就退出，这时候不该弹"要丢掉吗"。
        baseline = signature()
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
                onBack = { requestClose() },
                backIcon = Icons.Filled.Close,
                backLabel = "关闭"
            )
        },
        bottomBar = {
            // 保存挪到底部常驻。原来顶部有「保存」、表单末尾还有一颗大按钮，
            // 但两颗都在"滚动的尽头"或"视线之外"——放在底栏是手机上最不会漏的位置。
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 8.dp
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    if (isEdit) {
                        TextButton(onClick = { remove() }, enabled = !busy) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Button(
                        onClick = { commit() },
                        enabled = !busy && !loading,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text(if (isEdit) "保存修改" else "保存")
                        }
                    }
                }
            }
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
            // 粘贴区：一个真的能输入的多行文本框。
            // 用户原话：「顶部的'点击这里粘贴'应该设为一个文字框，而不应该是点击后
            // 就自动获取了」—— 所以这里不再"点一下偷偷读剪贴板"，而是给一个
            // 看得见、放得进东西的框，识别结果在框下面实时更新。
            if (!isEdit) {
                PasteField(
                    text = pasteText,
                    onTextChange = { pasteText = it },
                    rows = pasteRows,
                    resetKey = pasteToken,
                    enabled = !busy,
                    onReadClipboard = { readClipboardIntoField() },
                    onClear = { pasteText = "" },
                    onFill = { fillFromPaste(it) },
                    onBatch = { batchRows = pasteRows }
                )
                Spacer(Modifier.height(14.dp))
            } else {
                // 编辑时先报一遍"这里到底能改什么"。
                // 用户原话："点击编辑里只能更改标题名，并没有其他内容" —— 字段其实都在，
                // 只是排在一屏之外，眼睛扫到第一个输入框就以为到头了。
                // 一行字比再加十个控件都管用。
                EditableHint()
                Spacer(Modifier.height(10.dp))
            }

            VaultSectionTitle("基本信息")

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

            Spacer(Modifier.height(16.dp))
            VaultSectionTitle("备注、分类与补充")

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
                // 分类从 16 个扩到 34 个以后，一整片 chip 会把「备注」「自定义字段」
                // 顶到好几屏以外 —— 用户说的"编辑里只有标题"有一半是这么来的：
                // 想看的东西确实在，只是要往下滑很久才碰得到。
                // 改成"只看当前选中的那个 + 一个选择入口"，完整列表放进弹层，
                // 弹层里带搜索和「新建分类」，不用退出去到分类管理页。
                val currentCategory = categories.firstOrNull { it.id == categoryId }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = currentCategory != null,
                        onClick = { categoryPickerOpen = true },
                        label = { Text(currentCategory?.name ?: "未分类") },
                        enabled = !busy
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { categoryPickerOpen = true },
                        enabled = !busy
                    ) { Text(if (currentCategory == null) "选一个" else "更换") }
                    if (currentCategory != null) {
                        TextButton(
                            onClick = { categoryId = null },
                            enabled = !busy
                        ) { Text("清除") }
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
        }
    }

    batchRows?.let { rows ->
        BatchSaveDialog(
            rows = rows,
            busy = busy,
            onDismiss = { batchRows = null },
            onConfirm = { picked, onlyTitlePassword -> saveBatch(picked, onlyTitlePassword) }
        )
    }

    // 系统返回键与左上角关闭走同一条路：改过就问一句，没改直接走。
    // Compose 里不接这个的话，返回键会把整页弹掉，用户写的密码直接没了。
    BackHandler(enabled = !busy && dirty()) { confirmDiscard = true }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(if (isEdit) "不保存就退出？" else "这条不保存了？") },
            text = { Text("刚改的内容还没存，退出去就没了。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onDone()
                }) { Text("退出", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("继续写") }
            }
        )
    }

    if (categoryPickerOpen) {
        CategoryPickerDialog(
            categories = categories,
            selectedId = categoryId,
            onSelect = { id ->
                categoryId = id
                categoryPickerOpen = false
            },
            onCreate = { name ->
                scope.launch {
                    val created = runCatching { repo.createCategory(name) }.getOrNull()
                    if (created != null) {
                        categories = repo.categories()
                        categoryId = created
                        categoryPickerOpen = false
                    } else {
                        error = "分类没建成，换个名字再试"
                    }
                }
            },
            onDismiss = { categoryPickerOpen = false }
        )
    }
}

/**
 * 编辑态顶部的一句说明。
 *
 * 用户反馈"点编辑只能改标题，没有其他内容"——字段一直都在，
 * 但它们排在标题下面、一屏之外，视觉上一眼看不到就等于不存在。
 * 与其继续堆控件，不如先说清楚这里到底能改哪些东西。
 */
@Composable
private fun EditableHint() {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "标题、账号、密码、网址、动态码、备注、分类、收藏、自定义字段都能在这里改，往下滑就能看到。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

/**
 * 分类选择弹层。
 *
 * 三个理由做成弹层，而不是继续把 chip 铺在表单里：
 * 1. 分类扩到三十多个以后，铺开会把备注、自定义字段顶到好几屏以外 ——
 *    用户以为"编辑里只有标题"，其实是其余的都被挤下去了；
 * 2. 铺开的列表没有搜索，找"医疗"要一行行看过去；
 * 3. 想新建分类还得退出去到分类管理页，回来重新进编辑，中间表单内容全丢。
 *
 * 搜索用的是全项目统一的那把尺子（归一化 + 拼音首字母），
 * 所以输 `yl` 能搜到"医疗"，输 `yiliao` 也行。
 */
@Composable
private fun CategoryPickerDialog(
    categories: List<PlainCategory>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }

    val filtered = remember(query, categories) {
        val q = Normalize.forSearch(query)
        if (q.isEmpty()) {
            categories
        } else {
            categories.filter { c ->
                Normalize.forSearch(c.name).contains(q) || Pinyin.searchKey(c.name).contains(q)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    text = "选择分类",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索，输入首字母也行") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))

                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    item {
                        CategoryPickerRow(
                            name = "未分类",
                            selected = selectedId == null,
                            onClick = { onSelect(null) }
                        )
                    }
                    items(filtered, key = { it.id }) { c ->
                        CategoryPickerRow(
                            name = c.name,
                            selected = selectedId == c.id,
                            onClick = { onSelect(c.id) }
                        )
                    }
                    if (filtered.isEmpty() && query.isNotBlank()) {
                        item {
                            Text(
                                text = "没有叫「${query.trim()}」的分类，下面可以直接建一个",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(
                    Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                if (creating) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            placeholder = { Text("新分类名字") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                val trimmed = newName.trim()
                                if (trimmed.isNotEmpty()) onCreate(trimmed)
                            },
                            enabled = newName.isNotBlank()
                        ) { Text("建立") }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                // 搜索框里有字就顺手当新名字，省得再敲一遍。
                                if (newName.isBlank() && query.isNotBlank()) newName = query.trim()
                                creating = true
                            }
                        ) { Text("新建分类") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text("关闭") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryPickerRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp)
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
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
                                        append(if (user.isNullOrBlank()) "只有密码" else "账号 $user")
                                        row.password?.takeIf { it.isNotEmpty() }
                                            ?.let { append(" · 密码 $it") }
                                        row.notes?.takeIf { it.isNotBlank() }
                                            ?.let { append(" · 备注 $it") }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
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
 * 密码照原文显示。早先是打点的，理由是怕旁人看见；但那点保护本来就没有意义 ——
 * 原文就在上面的输入框里躺着，打点只让用户核对不了"切得对不对"，
 * 而这正是这张卡唯一的作用。
 */
private fun describeCandidate(candidate: CredentialParser.Result): String = buildList {
    candidate.title?.let { add("标题  $it") }
    candidate.username?.let { add("账号  $it") }
    candidate.password?.let { add("密码  $it") }
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
 * 早先这里是一整块可点的卡片，点一下就去读剪贴板 —— 用户的反馈是
 * 「应该设为一个文字框，而不应该是点击后就自动获取了」。现在它是一个真正的
 * 多行输入框：想贴就贴、想手打就手打，读写剪贴板变成一个需要单独点的动作。
 *
 * 框下面实时给识别结果，分三种：
 * - 什么都没认出来：只说一句"一行一条最稳妥"，不弹错误；
 * - 认出 1 条：把结果摊开，点「填入表单」才写进去；
 * - 认出多条：**每一条都列出来**（见 [CandidateListCard]）。早先这里只报一句
 *   「认出 2 组账号密码」，用户的原话是"应该全部把识别出来的列出来" ——
 *   数量没有信息量，他要看的是这十条分别是什么，好在存之前剔掉认错的。
 *
 * 特意不做"边打边自动填"：用户手打时每敲下一个数字都会触发一次识别，
 * 表单会在眼皮底下被反复改写。
 */
@Composable
private fun PasteField(
    text: String,
    onTextChange: (String) -> Unit,
    rows: List<CredentialParser.Result>,
    resetKey: Int,
    enabled: Boolean,
    onReadClipboard: () -> Unit,
    onClear: () -> Unit,
    onFill: (CredentialParser.Result) -> Unit,
    onBatch: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PasteGlyph(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "粘贴或输入账号密码",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Spacer(Modifier.height(8.dp))

        // resetKey 变一次就重造这个输入框：从剪贴板整体灌入时，Compose 的
        // 输入框偶尔不把新文字画出来（下面却已经认出两组），换 key 最省事。
        key(resetKey) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = {
                    // 示例一律用编造的值。真实密码绝不该出现在界面文案、截图或仓库里。
                    Text("一行一条，也可以写成「账号：xxx 密码：xxx」\n例如：某某平台密码 abcd1234")
                },
                minLines = 3,
                maxLines = 7,
                enabled = enabled,
                // 等宽字体：密码里 0 与 O、1 与 l 的差别，一眼能看出来
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 灌进来的东西到底有没有进来，用它自己说一句 —— 用户的原话是
        // "读出剪贴板后剪贴板不显示"，光靠输入框没法确认是不是空的。
        if (text.isNotEmpty()) {
            Text(
                text = "${text.lines().size} 行 · ${text.length} 字",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onReadClipboard, enabled = enabled) {
                Text("读剪贴板")
            }
            Spacer(Modifier.weight(1f))
            if (text.isNotEmpty()) {
                TextButton(onClick = onClear, enabled = enabled) { Text("清空") }
            }
        }

        when {
            text.isBlank() -> Unit
            rows.isEmpty() -> Text(
                text = "还没认出账号或密码。一行一条最稳妥，每行写成「平台名 + 密码」。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            rows.size == 1 -> SingleCandidateCard(rows[0], enabled, onFill)
            else -> CandidateListCard(rows, enabled, onFill, onBatch)
        }
    }
}

/** 认出 1 条时摊开给人核对；认反了就一键对调。密码照原文显示，见 [describeCandidate]。 */
@Composable
private fun SingleCandidateCard(
    row: CredentialParser.Result,
    enabled: Boolean,
    onFill: (CredentialParser.Result) -> Unit
) {
    // 只在当前这张卡片上生效：粘贴的文字一换，remember(row) 就会重置
    var swapped by remember(row) { mutableStateOf(false) }
    val shown = if (swapped) row.swapped() else row

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = "认出这一条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = describeCandidate(shown),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                // 位置猜测必然有错判的时候（`抖音 abc123` 到底谁是谁只有用户知道），
                // 与其把规则调得更聪明，不如给一个一键对调
                if (row.username != null && row.password != null) {
                    TextButton(
                        onClick = { swapped = !swapped },
                        enabled = enabled
                    ) {
                        Text(if (swapped) "换回来" else "账号密码反了？")
                    }
                }
                TextButton(onClick = { onFill(shown) }, enabled = enabled) { Text("填入表单") }
            }
        }
    }
}

/**
 * 认出多条时把每一条都摊在框下面。
 *
 * 早先这里只写一句「认出 2 组账号密码」加一个「看看」按钮。用户的原话是
 * "下面应该就识别出来具体有哪些账号密码之类的…应该全部把识别出来的列出来"——
 * 数量本身没有信息量，他要看的是这两条分别是什么，好在落库之前剔掉认错的。
 *
 * 每条都能点：点谁就把谁填进表单；也能一次全存（进 [BatchSaveDialog] 逐条勾选）。
 */
@Composable
private fun CandidateListCard(
    rows: List<CredentialParser.Result>,
    enabled: Boolean,
    onFill: (CredentialParser.Result) -> Unit,
    onBatch: () -> Unit
) {
    // 逐条的对调状态：位置猜测必然有错判，摊开列出来时更得给每条留退路
    var swapped by remember(rows) { mutableStateOf(emptySet<Int>()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = "认出 ${rows.size} 组",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "点哪一条就把它填进表单；也可以一次全存。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))

            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                rows.forEachIndexed { index, row ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    }
                    val flipped = index in swapped
                    CandidateRow(
                        index = index,
                        row = if (flipped) row.swapped() else row,
                        canSwap = row.username != null && row.password != null,
                        swapped = flipped,
                        enabled = enabled,
                        onSwap = {
                            swapped = if (flipped) swapped - index else swapped + index
                        },
                        onClick = { onFill(if (flipped) row.swapped() else row) }
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onBatch, enabled = enabled) { Text("一次全存") }
            }
        }
    }
}

/** 摊开时的一行：序号 + 标题 + 账号/密码/备注/网址，右侧可选「反了」。 */
@Composable
private fun CandidateRow(
    index: Int,
    row: CredentialParser.Result,
    canSwap: Boolean,
    swapped: Boolean,
    enabled: Boolean,
    onSwap: () -> Unit,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = "${index + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(18.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = row.title ?: row.url ?: row.username ?: "未命名",
                style = MaterialTheme.typography.bodyMedium
            )
            // 密码照原文显示，理由同 [describeCandidate]：原文就在上面的输入框里，
            // 打点只会让人核对不了切得对不对。
            row.username?.takeIf { it.isNotBlank() }?.let { CandidateDetail("账号", it) }
            row.password?.takeIf { it.isNotEmpty() }?.let { CandidateDetail("密码", it) }
            row.notes?.takeIf { it.isNotBlank() }?.let { CandidateDetail("备注", it) }
            if (row.title != null) {
                row.url?.takeIf { it.isNotBlank() }?.let { CandidateDetail("网址", it) }
            }
        }
        if (canSwap) {
            TextButton(onClick = onSwap, enabled = enabled) {
                Text(
                    text = if (swapped) "换回" else "反了",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

/** 「标签  值」一行，值用等宽字体，0 与 O、1 与 l 的差别一眼能看出来。 */
@Composable
private fun CandidateDetail(label: String, value: String) {
    Row {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(34.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace
        )
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
