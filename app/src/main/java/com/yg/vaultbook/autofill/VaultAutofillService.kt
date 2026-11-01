package com.yg.vaultbook.autofill

import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.util.Log
import com.yg.vaultbook.App
import com.yg.vaultbook.core.matcher.CaptureConfidence
import com.yg.vaultbook.core.matcher.CaptureVerdict
import com.yg.vaultbook.core.matcher.MatchCandidate
import com.yg.vaultbook.core.util.Normalize
import com.yg.vaultbook.data.VaultRepository
import com.yg.vaultbook.data.entity.PendingCaptureEntity
import com.yg.vaultbook.domain.CaptureInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 自动填充服务。**一个类同时承担三件事**：
 *
 * 1. **填**：`onFillRequest` 把库里匹配当前站点的条目做成候选，交给系统弹窗；
 * 2. **捕获**：`onSaveRequest` 在用户提交登录表单后拿到字段值；
 * 3. **兜住锁定状态**：保险库锁着时仍然返回带 `SaveInfo` 的响应，
 *    并把捕获写进 Keystore 加密的临时文件（[com.yg.vaultbook.data.KeystoreCaptureQueue]）。
 *
 * ── 第 3 点为什么是重点 ─────────────────────────────────────
 * `onSaveRequest` 只在本 service **本次** `onFillRequest` 里返回过含 `SaveInfo`
 * 的响应时才会被系统回调。所以「锁定时为了安全什么都不返回」的真实后果是：
 * 用户手动输入的密码**永远抓不到** —— 而这恰好是最常见的场景
 * （打开浏览器登录某个网站时，本 App 早就自动锁了）。
 *
 * ── 这里不做什么 ────────────────────────────────────────────
 * - 不记录页面文本，不解密任何条目到日志（release 构建里连 `Log.d` 都被剥离）；
 * - 不猜域名：`webDomain` 为空时宁可只存包名，也不编一个站点出来；
 * - 不在 `onSaveRequest` 里做重活：先把数据落盘，再回调 `onSuccess`。
 */
class VaultAutofillService : AutofillService() {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val gateway get() = (applicationContext as App).gateway

    private val builder by lazy { AutofillResponseBuilder(this) }

    override fun onConnected() {
        super.onConnected()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onDisconnected() {
        scope.cancel()
        super.onDisconnected()
    }

    // ── 填充 ────────────────────────────────────────────────────

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        val parsed = AssistStructureParser.parse(request.fillContexts.lastOrNull()?.structure)

        // 没有密码框的页面（搜索框、地址栏、验证码框）不值得占用系统弹窗，
        // 也没有需要保存的东西 —— 直接交空响应
        if (!parsed.hasPassword) {
            // FillCallback 只有 onSuccess(FillResponse) / onFailure(CharSequence)，
            // 没有 finish()。给不出响应时必须走 onFailure（onSuccess 参数非空）。
            callback.onFailure(null)
            return
        }

        val saveInfo = builder.saveInfo(parsed)
        if (saveInfo == null) {
            // 有密码框却凑不出 SaveInfo（拿不到 autofillId），此时任何 FillResponse
            // 都构造不出来。只能放弃本次捕获，但不能让服务抛异常。
            callback.onFailure(null)
            return
        }

        val repo = gateway.repositoryOrNull()

        // 锁定 / 仓库未就绪：**只给 SaveInfo**。
        // 用户仍然能手动输入密码登录，而这次的输入会被 onSaveRequest 捕获到。
        if (repo == null) {
            val onlySave = builder.saveOnly(saveInfo)
            if (onlySave != null) callback.onSuccess(onlySave) else callback.onFailure(null)
            return
        }

        scope.launch {
            if (cancellationSignal.isCanceled) return@launch

            val host = Normalize.hostFromUrl(parsed.webDomain)
            // 浏览器里 webDomain 是网站域名；原生 App 里没有 webDomain，包名才有意义。
            // 两者同时出现的情况极少，优先按域名匹配。
            val pkg = if (host == null) parsed.packageName else null

            val candidates: List<MatchCandidate> = try {
                repo.candidatesFor(host, pkg, parsed.usernameField?.text)
            } catch (e: Exception) {
                // 解不开（锁定瞬间密钥被清零）就当没有候选，SaveInfo 照给
                emptyList()
            }

            if (cancellationSignal.isCanceled) return@launch

            val datasets = candidates.mapNotNull { candidate ->
                try {
                    buildDataset(repo, candidate, parsed)
                } catch (e: Exception) {
                    null
                }
            }

            try {
                val response = builder.withDatasets(datasets, saveInfo)
                if (response != null) callback.onSuccess(response) else callback.onFailure(null)
            } catch (e: Exception) {
                // 响应构造出了意外（进程已失效等）。再交一次只带 SaveInfo 的响应，
                // 至少别让捕获能力丢掉。
                runCatching {
                    val onlySave = builder.saveOnly(saveInfo)
                    if (onlySave != null) callback.onSuccess(onlySave) else callback.onFailure(null)
                }
            }

            datasets.forEach { it.password?.fill('\u0000') }
        }
    }

    /**
     * 把一条匹配结果做成候选。
     *
     * 密码只在"页面上确实有密码框"时才解密，且交给 Builder 之后立刻清零。
     */
    private suspend fun buildDataset(
        repo: VaultRepository,
        candidate: MatchCandidate,
        parsed: ParsedLoginForm
    ): VaultDataset? {
        val summary = repo.summaryOf(candidate.entryId) ?: return null

        // 密码框不存在时不解密密码：没有地方用它，解密只是徒增暴露面
        val password = if (parsed.passwordField != null) {
            repo.revealPassword(candidate.entryId)
        } else {
            null
        }

        val canFillUsername = parsed.usernameField != null && !summary.username.isNullOrEmpty()
        // CharArray 没有 isNullOrEmpty（那是 CharSequence 的扩展），分开判
        val canFillPassword = parsed.passwordField != null && password != null && password.isNotEmpty()

        // 一个字段都填不上就不给候选 —— 用户点了没反应是坏体验
        if (!canFillUsername && !canFillPassword) {
            password?.fill('\u0000')
            return null
        }

        return VaultDataset(
            entryId = summary.id,
            label = summary.title,
            sublabel = summary.username ?: summary.hostNorm,
            usernameField = parsed.usernameField,
            passwordField = parsed.passwordField,
            username = summary.username,
            password = password
        )
    }

    // ── 保存 ────────────────────────────────────────────────────

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val parsed = AssistStructureParser.parse(request.fillContexts.lastOrNull()?.structure)
        val gw = gateway

        val input = buildCaptureInput(parsed)
        if (input == null) {
            callback.onSuccess()
            return
        }

        val captureRepo = gw.captureRepositoryOrNull()

        if (captureRepo != null) {
            // 解锁状态：直接进库，用户马上能在"待确认"里看到。
            callback.onSuccess()
            scope.launch {
                try {
                    captureRepo.record(input)
                } catch (e: Exception) {
                    Log.w(TAG, "捕获落库失败")
                } finally {
                    input.clearSecrets()
                }
            }
            return
        }

        // 锁定状态：**先落盘再回调**。顺序不能反 —— 先 onSuccess 的话，
        // 系统可能在写文件之前就把本进程回收掉，这条捕获永久消失。
        val queued = gw.captureQueue.enqueue(input)
        input.clearSecrets()
        if (!queued) Log.w(TAG, "锁定期间捕获未能写入缓冲")
        callback.onSuccess()
    }

    /**
     * 把解析出的表单变成一条待确认捕获。
     *
     * `submitted` 恒为 true：系统只在用户提交表单后才调 `onSaveRequest`，
     * 这本身就是"这是一次真实登录"最强的证据。
     */
    private fun buildCaptureInput(parsed: ParsedLoginForm): CaptureInput? {
        val password = parsed.passwordField?.text
            ?.takeIf { it.isNotEmpty() }
            ?.toCharArray()
            ?: return null

        val host = Normalize.hostFromUrl(parsed.webDomain)
        // 有域名时包名必然是浏览器，存下来只会在"按包名匹配"里造成误命中
        val pkg = if (host == null) parsed.packageName?.takeIf { it != packageName } else null

        val score = CaptureConfidence.score(
            hasPassword = true,
            hasUsername = parsed.hasUsername,
            hasSiteIdentity = host != null || pkg != null,
            submitted = true,
            hasTitle = !parsed.activityTitle.isNullOrBlank()
        )

        if (CaptureConfidence.verdict(score) == CaptureVerdict.DISCARD) {
            password.fill('\u0000')
            return null
        }

        return CaptureInput(
            hostNorm = host,
            packageNorm = pkg,
            title = parsed.activityTitle,
            username = parsed.usernameField?.text?.takeIf { it.isNotEmpty() },
            password = password,
            source = PendingCaptureEntity.SOURCE_AUTOFILL,
            confidence = score,
            // 落库阶段再去匹配已有条目（那时才知道用户在哪个条目上操作过）
            matchedEntryId = null
        )
    }

    private companion object {
        const val TAG = "VaultAutofill"
    }
}
