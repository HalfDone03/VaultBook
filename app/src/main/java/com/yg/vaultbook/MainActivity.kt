package com.yg.vaultbook

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import com.yg.vaultbook.capture.CaptureNotifier
import com.yg.vaultbook.ui.VaultApp
import com.yg.vaultbook.ui.purgeSecretClip
import com.yg.vaultbook.ui.theme.VaultTheme

/**
 * 唯一的 Activity。
 *
 * 所有屏幕切换都在 Compose 内部（见 [VaultApp]），不新开 Activity —— 这样
 * 生命周期只有一处，不用在多个 Activity 之间传递状态。
 *
 * ── 通知点击的入口 ────────────────────────────────────────────
 * 捕获通知带 [CaptureNotifier.EXTRA_OPEN_PENDING]，点进来要直接落到待确认页。
 * 两条路径都要处理：
 * - 进程不在 → `onCreate` 收到；
 * - 进程还在、Activity 在栈里 → 因为 Intent 带了 `FLAG_ACTIVITY_CLEAR_TOP`，
 *   系统走 `onNewIntent`，不会再调 `onCreate`。
 * 漏掉后者的话，"应用在后台时点通知"会停在原来的页面上，看起来像通知没生效。
 *
 * 用递增计数而不是布尔值：同一个布尔值连续两次置 true 不会触发 Compose 重组，
 * 用户在待确认页按返回后、第二次点通知就会失效。
 *
 * ── 外部分享的入口 ───────────────────────────────────────────
 * `ACTION_SEND` + `text/plain` 同样要在 `onCreate` 与 `onNewIntent` 两处都读，
 * 理由同上。文本放在 [sharedText] 里交给 Compose，编辑页识别完会回调清空 ——
 * 不清的话，从最近任务恢复时会反复用同一段文本去填新建页。
 *
 * ── 桌面快捷方式的入口 ───────────────────────────────────────
 * 长按桌面图标弹出的「新建」「生成密码」走 `vaultbook://new` 与
 * `vaultbook://generator`。同样要 onCreate / onNewIntent 两处都读。
 * 读完要把 `action` 和 `data` 都抹掉，否则从最近任务恢复时会又跳一次。
 *
 * ── 桌面小组件的入口 ─────────────────────────────────────────
 * 点 widget 上某一行走 `vaultbook://copy/<entryId>`。这一条**不能**在后台
 * 直接复制：Android 10 起后台进程写剪贴板会被系统静默丢弃。所以只能是
 * "拉起应用 + 由前台复制"，这里只负责把 id 交给 Compose。
 *
 * ── 为什么是普通的 ComponentActivity ─────────────────────────
 * 指纹解锁已随"去主密码"改造一起删掉（连同 `androidx.biometric` 与
 * `androidx.fragment` 两个依赖），所以不再需要 `FragmentActivity` 承载
 * `BiometricPrompt`。用最小的 `ComponentActivity` 就够 `setContent`。
 */
class MainActivity : ComponentActivity() {

    // 这六个是**一次性触发信号**（通知点进来 / 外部分享 / 深链 / 小组件），
    // 不是界面状态，所以刻意不做 onSaveInstanceState 持久化：
    // 重建时重新走一遍"跳转"会带来副作用（比如小组件那条会再复制一次密码）。
    // 界面停在哪一页由 VaultApp 里的 ScreenSaver 负责，两者分工不重叠。
    private var openPendingSignal by mutableIntStateOf(0)
    private var sharedText by mutableStateOf<String?>(null)
    private var deepLinkTarget by mutableStateOf<String?>(null)
    private var deepLinkSignal by mutableIntStateOf(0)
    private var copyEntryId by mutableStateOf<String?>(null)
    private var copySignal by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15（targetSdk 35）起，系统强制所有应用画到状态栏与导航栏底下，
        // 不再由 `setDecorFitsSystemWindows` 决定。主动调一次 `enableEdgeToEdge()`
        // 让 26~34 上也走同一套布局 —— 否则低版本是"内容被栏挡住"，
        // 高版本是"内容缩在里面"，同一份 Compose 代码在两代系统上要各调一次边距。
        //
        // 各页面的安全区由各自的 `Scaffold` 负责（默认 contentWindowInsets 就是
        // 系统栏），这里只把窗口让给系统栏绘制。
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            VaultTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VaultApp(
                        openPendingSignal = openPendingSignal,
                        sharedText = sharedText,
                        onSharedTextConsumed = { sharedText = null },
                        deepLinkTarget = deepLinkTarget,
                        deepLinkSignal = deepLinkSignal,
                        copyEntryId = copyEntryId,
                        copySignal = copySignal,
                        onCopyConsumed = { copyEntryId = null }
                    )
                }
            }
        }
    }

    /**
     * 回到前台时补一次剪贴板清理。
     *
     * 进程被系统杀掉后，复制密码时挂的那个 `postDelayed` 也跟着没了；
     * 到期时间落在 SharedPreferences 里，这里读出来补做 —— 兜住
     * "复制完切走、App 被杀、半小时后回来粘贴还能粘出密码"这条路径。
     *
     * 只有前台才读得到剪贴板（Android 12+ 限制），所以必须放在 onResume。
     */
    override fun onResume() {
        super.onResume()
        purgeSecretClip(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        if (intent.getBooleanExtra(CaptureNotifier.EXTRA_OPEN_PENDING, false)) {
            openPendingSignal++
            // 抹掉标记：Activity 被重新拉起（比如从最近任务恢复）时会复用同一个 Intent，
            // 不清掉就会每次都跳到待确认页。
            intent.removeExtra(CaptureNotifier.EXTRA_OPEN_PENDING)
        }

        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!text.isNullOrBlank()) {
                sharedText = text
                // 同上：不清 extra，从最近任务恢复时会重复进入新建页
                intent.removeExtra(Intent.EXTRA_TEXT)
                intent.action = null
            }
        }

        if (intent.action == Intent.ACTION_VIEW) {
            val uri = intent.data
            if (uri != null && uri.scheme == "vaultbook") {
                val host = uri.host.orEmpty()
                if (host == "new" || host == "generator") {
                    deepLinkTarget = host
                    deepLinkSignal++
                    // action 与 data 都得清：只清 action 的话，下次恢复时
                    // `data` 还在，某些 ROM 会按 data 重新走一遍 VIEW 分发
                    intent.action = null
                    intent.data = null
                } else if (host == "copy") {
                    // vaultbook://copy/<entryId>，entryId 在 path 段
                    val id = uri.pathSegments?.firstOrNull()
                    if (!id.isNullOrBlank()) {
                        copyEntryId = id
                        copySignal++
                        intent.action = null
                        intent.data = null
                    }
                }
            }
        }
    }
}
