package com.yg.vaultbook.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.yg.vaultbook.App
import com.yg.vaultbook.capture.CaptureNotifier
import com.yg.vaultbook.core.matcher.CaptureConfidence
import com.yg.vaultbook.core.matcher.CaptureVerdict
import com.yg.vaultbook.data.entity.PendingCaptureEntity
import com.yg.vaultbook.domain.CaptureInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 增强捕获服务 —— 负责「抓」。
 *
 * ── 为什么需要它 ──────────────────────────────────────────────
 * Autofill 依赖 `AssistStructure` 里的 `autofillId`，而 Flutter / React Native /
 * 游戏内嵌 WebView 等自绘登录页根本不提供，系统不会发起填充请求，`onSaveRequest`
 * 也就永远不会来。无障碍服务直接读节点树，是这类页面的唯一通道。
 *
 * ── 触发时机（这条最关键）────────────────────────────────────
 * **不是**持续读屏。只在两种时候读根节点树：
 *
 * 1. 收到「点击」事件，且被点的控件文案是提交类按钮（登录 / 登陆 / sign in …）。
 *    此刻页面还没跳走，密码框里还有值 —— 这是唯一能拿到它的瞬间。
 * 2. 上一个动作是点提交，但页面紧接着切走了（某些 WebView 按钮不带文本，
 *    靠第 1 条认不出来）。此时用切换前缓存的那份快照补一次。
 *
 * 其余事件只做一件轻量的事：在节流窗口内刷新一份"最近看到的表单"快照，
 * 给第 2 条兜底。**快照有 10 秒存活期**，过期即丢，不让密码在内存里躺着。
 *
 * ── 边界（务必保持）───────────────────────────────────────────
 * 1. **只读**：`canPerformGestures=false`，绝不用 `performAction` 模拟点击。
 * 2. **只取登录页**：没有密码框的页面在识别阶段就被丢掉，不做全树遍历。
 * 3. **不猜域名**：见 [BrowserHosts]。拿不到真域名就只按包名匹配。
 * 4. **跳过注册页**：见 [A11yLoginForm.looksLikeRegistration]。
 * 5. **release 不打节点文本**：那等于把密码写进 logcat。本文件所有日志只输出
 *    计数与结果，不输出任何 `text`。
 */
class CaptureAccessibilityService : AccessibilityService() {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var notifier: CaptureNotifier? = null

    /** 上次扫描根节点的时间（uptime，不受用户改系统时间影响）。 */
    private var lastScanAt = 0L

    /** 最近一次在"像登录页"的页面上看到的表单快照，给窗口切换场景兜底。 */
    private var cachedForm: A11yLoginForm? = null
    private var cachedAt = 0L

    /** 上一个动作是点提交、但还没成功抓到密码。 */
    private var awaitingArrival = false

    /** 上次真正落库的时间，防止同一页面反复捕获。 */
    private var lastCaptureAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        notifier = CaptureNotifier(this).also { it.ensureChannel() }
        Log.i(TAG, "增强捕获服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (pkg in IGNORED_PACKAGES) return

        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val label = e.text?.joinToString(" ")
                val hitSubmit = A11yFormDetector.isSubmitLabel(label)
                // 第二个信号：这次点击落在"刚看到过登录表单"的页面上（缓存未过期）。
                // WebView 里的自定义按钮常常点击事件不带文本，光看文案会漏掉。
                val hadLoginForm = cachedForm != null &&
                    SystemClock.uptimeMillis() - cachedAt <= CACHE_TTL_MS

                if (hitSubmit || hadLoginForm) {
                    // 先尝试当场读树：页面还没跳走，这一秒之后表单可能就没了。
                    val form = readForm(pkg)
                    val recorded = form != null && form.looksLikeLogin &&
                        capture(form, pkg, submitted = true)
                    // 没抓到就等窗口切换事件，用切换前的缓存快照补一次。
                    awaitingArrival = !recorded
                } else {
                    refreshCache(pkg)
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (awaitingArrival) {
                    awaitingArrival = false
                    val cached = cachedForm
                    if (cached != null && SystemClock.uptimeMillis() - cachedAt <= CACHE_TTL_MS) {
                        capture(cached, pkg, submitted = true)
                    }
                }
                refreshCache(pkg)
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> refreshCache(pkg)

            else -> Unit
        }
    }

    override fun onInterrupt() {
        // 系统要求实现，无需处理
    }

    override fun onDestroy() {
        scope.cancel()
        // 服务被关掉时把缓存里的密码抹掉，别留给进程里的下一个使用者
        cachedForm = null
        super.onDestroy()
    }

    // ── 扫描 ─────────────────────────────────────────────────────

    /** 节流刷新快照。文本变化事件一秒能来十几次，不节流会拖垮输入框。 */
    private fun refreshCache(pkg: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastScanAt < THROTTLE_MS) return
        lastScanAt = now

        val form = readForm(pkg) ?: return
        if (!form.looksLikeLogin) {
            cachedForm = null
            return
        }
        cachedForm = form
        cachedAt = now
    }

    /**
     * 把当前活动窗口的节点树拍平成快照列表。
     *
     * 用**迭代**而不是递归：无障碍节点树由第三方应用构造，递归在几万层深的
     * 恶意/异常树上会 `StackOverflowError`，而这只发生在无障碍服务的回调里 ——
     * 崩溃会被系统记成服务异常，直接影响自动填充是否还愿意被调用。
     */
    @Suppress("DEPRECATION")
    private fun readForm(pkg: String): A11yLoginForm? {
        val sheets = readWindowNodes()
        if (sheets.isEmpty()) return null
        // 有些 ROM 把网页内容放在**另一个窗口**里（小米浏览器、部分 WebView），
        // `rootInActiveWindow` 只看得到宿主自己的工具栏 —— 那样连密码框都读不到，
        // 表现就是「开了无障碍但什么都抓不到」。所以把所有窗口都拍平，
        // 优先选含密码框的那个窗口，其次含任意输入框的，最后才用第一个。
        val chosen = sheets.firstOrNull { nodes -> nodes.any { it.visible && it.password } }
            ?: sheets.firstOrNull { nodes -> nodes.any { it.visible && it.editable } }
            ?: sheets.first()
        val browser = isBrowserLike(pkg)
        return A11yFormDetector.detect(
            chosen,
            pageChrome = if (browser) appChromeLabels(pkg) else emptySet(),
            preferContentTitle = browser
        )
    }

    /**
     * 把当前每个窗口的节点树各拍平一份。
     *
     * 只读 `rootInActiveWindow` 会漏掉"网页在独立窗口"的情况（小米浏览器、
     * 部分定制 WebView），那种环境下密码框根本不在活动窗口里。
     * 单个窗口读取失败不影响其它窗口；全部都拿不到才退回活动窗口。
     */
    @Suppress("DEPRECATION")
    private fun readWindowNodes(): List<List<NodeSnapshot>> {
        val out = ArrayList<List<NodeSnapshot>>(4)
        val opened = runCatching { windows }.getOrNull()
        if (opened != null) {
            for (window in opened) {
                val root = runCatching { window.root }.getOrNull() ?: continue
                val nodes = ArrayList<NodeSnapshot>(64)
                try {
                    flatten(root, nodes)
                } catch (e: Exception) {
                    // 这棵树坏了就跳过它，别让一个窗口拖垮整次读取
                } finally {
                    runCatching { root.recycle() }
                }
                if (nodes.isNotEmpty()) out.add(nodes)
            }
        }
        if (out.isEmpty()) {
            val root = runCatching { rootInActiveWindow }.getOrNull() ?: return emptyList()
            val nodes = ArrayList<NodeSnapshot>(64)
            try {
                flatten(root, nodes)
            } catch (e: Exception) {
                return emptyList()
            } finally {
                runCatching { root.recycle() }
            }
            if (nodes.isNotEmpty()) out.add(nodes)
        }
        return out
    }

    /**
     * 这个包名是不是浏览器：白名单里有，或者它的应用名带「浏览器」。
     *
     * 光靠白名单会漏掉定制 ROM 自带的浏览器（小米/红米那只就叫「浏览器」），
     * 漏掉之后标题又会退回"最浅节点"，记下来一条叫「浏览器」的账号。
     */
    private fun isBrowserLike(pkg: String): Boolean =
        BrowserHosts.isBrowser(pkg) || BrowserHosts.looksLikeBrowserLabel(appChromeLabels(pkg))

    /**
     * 宿主应用自己的界面文案（浏览器名）。
     *
     * 浏览器里"最浅的文本节点"永远是它自己的工具栏，标题会变成「夸克」「Chrome」。
     * 这里取到 App 名交给识别层排除，让标题落到网页正文上。
     *
     * 取不到（包名不对、权限异常）就返回空集，退化成原来的行为，不影响捕获。
     */
    @Suppress("DEPRECATION")
    private fun appChromeLabels(pkg: String): Set<String> = runCatching {
        val label = packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0))
            ?.toString()
            ?.trim()
        buildSet {
            if (!label.isNullOrEmpty()) {
                add(label)
                // 「夸克」与「夸克浏览器」会同时出现在工具栏与标签栏上
                add(label + "浏览器")
            }
        }
    }.getOrDefault(emptySet())

    @Suppress("DEPRECATION")
    private fun flatten(root: AccessibilityNodeInfo, out: MutableList<NodeSnapshot>) {
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)
        var visited = 0

        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = stack.removeLast()
            visited++
            out.add(snapshotOf(node, depth))

            if (depth < MAX_DEPTH) {
                // 逆序入栈，出栈才是文档顺序 —— 识别逻辑依赖"从上到下"这个顺序
                for (i in node.childCount - 1 downTo 0) {
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    stack.addLast(child to depth + 1)
                }
            }
            if (node !== root) runCatching { node.recycle() }
        }

        // 超限时栈里还压着一批没处理的节点，不回收会被框架判为泄漏
        while (stack.isNotEmpty()) {
            runCatching { stack.removeLast().first.recycle() }
        }
    }

    private fun snapshotOf(node: AccessibilityNodeInfo, depth: Int): NodeSnapshot = NodeSnapshot(
        className = node.className?.toString(),
        viewId = node.viewIdResourceName?.substringAfterLast('/'),
        text = node.text?.toString(),
        hint = node.hintText?.toString(),
        description = node.contentDescription?.toString(),
        editable = node.isEditable,
        password = node.isPassword,
        clickable = node.isClickable,
        visible = node.isVisibleToUser,
        depth = depth
    )

    // ── 落库 ─────────────────────────────────────────────────────

    /**
     * 一条捕获的完整判定与落库。
     *
     * 落库路径和自动填充完全一致：解锁时直接写进加密库，锁定时进 Keystore 队列文件。
     * 用户在浏览器点登录时，本应用几乎必然处于锁定状态（自动锁定 30 秒），
     * 所以**锁定这条路径是主路径**，不是兜底。
     *
     * @return 是否真的记下了一条（调用方据此决定要不要等窗口切换补抓）
     */
    private fun capture(form: A11yLoginForm, sourcePackage: String, submitted: Boolean): Boolean {
        val password = form.password
        if (password.isNullOrEmpty()) return false

        // 注册页/改密页：密码是"新设的"，多半还没生效。存下来会盖掉能用的那条。
        if (form.looksLikeRegistration) return false

        val now = SystemClock.uptimeMillis()
        if (now - lastCaptureAt < MIN_CAPTURE_GAP_MS) return false

        val gw = (applicationContext as App).gateway

        // 只有浏览器才尝试从地址栏认站点；其余应用一律只按包名。
        val browser = isBrowserLike(sourcePackage)
        val host = if (browser) BrowserHosts.hostFromCandidates(form.siteCandidates) else null
        // 浏览器里**绝不**拿包名当站点身份：`com.quark.browser` 不是站点，
        // 拿它当键会把夸克里打开的每个网站混成同一条（下次在任一站都可能被填进去）。
        // 认不出域名就退化成"按标题认"，至少不会把 A 站的密码挂到 B 站名下。
        val packageNorm = if (host == null && !browser) {
            sourcePackage.takeIf { it != packageName }
        } else {
            null
        }

        val score = CaptureConfidence.score(
            hasPassword = true,
            hasUsername = form.hasUsername,
            hasSiteIdentity = host != null || packageNorm != null || (browser && !form.pageTitle.isNullOrBlank()),
            submitted = submitted,
            hasTitle = !form.pageTitle.isNullOrBlank()
        )
        if (CaptureConfidence.verdict(score) == CaptureVerdict.DISCARD) {
            Log.d(TAG, "置信度不足，丢弃")
            return false
        }

        lastCaptureAt = now

        val input = CaptureInput(
            hostNorm = host,
            packageNorm = packageNorm,
            title = form.pageTitle,
            username = form.username,
            password = password.toCharArray(),
            source = PendingCaptureEntity.SOURCE_A11Y,
            confidence = score,
            matchedEntryId = null
        )

        val repository = gw.captureRepositoryOrNull()
        scope.launch {
            val count = try {
                if (repository != null) {
                    repository.record(input)
                    runCatching { repository.countPending() }.getOrDefault(0)
                } else {
                    gw.captureQueue.enqueue(input)
                    gw.captureQueue.count()
                }
            } catch (e: Exception) {
                // 无障碍服务里抛出异常会被系统判为服务异常，宁可静默失败
                Log.w(TAG, "捕获落库失败")
                0
            } finally {
                input.clearSecrets()
            }
            notifier?.notifyPending(count)
        }
        return true
    }

    companion object {
        private const val TAG = "VaultCapture"

        /** 文本变化事件的节流窗口。250ms 兼顾"看起来实时"与不打爆 CPU。 */
        private const val THROTTLE_MS = 250L

        /** 缓存快照的存活期。超过就丢掉，不让密码长期驻留内存。 */
        private const val CACHE_TTL_MS = 10_000L

        /** 两次落库的最小间隔，防同一页面反复触发。 */
        private const val MIN_CAPTURE_GAP_MS = 3_000L

        private const val MAX_NODES = 1_500
        private const val MAX_DEPTH = 40

        /**
         * 直接跳过的包名。
         *
         * 前三个是输入法（它们的候选栏里会出现刚输入的密码片段，绝不能扫）；
         * `systemui` 里是状态栏与通知；`permissioncontroller` 是权限弹窗。
         * `com.android.settings` 也排除 —— 系统设置里的"修改锁屏密码"页有密码框，
         * 而那是设备凭据，不属于本应用该管的账号。
         */
        private val IGNORED_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.android.settings",
            "com.google.android.inputmethod.latin",
            "com.sohu.inputmethod.sogou",
            "com.iflytek.inputmethod",
            "com.baidu.input",
            "com.tencent.qqpinyin",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller"
        )
    }
}
