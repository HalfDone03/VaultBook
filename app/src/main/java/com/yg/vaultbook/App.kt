package com.yg.vaultbook

import android.app.Application
import com.yg.vaultbook.core.crypto.VaultSession
import com.yg.vaultbook.data.VaultGateway

/**
 * 应用入口。
 *
 * 这里做两件事：建全局单例、建保险库编排器。
 *
 * ── 为什么不锁 ───────────────────────────────────────────────
 * 早先的设计是"冷启动必锁定 + 切后台 30 秒自动锁"，配套主密码。
 * 现在产品定位改成"比记事本顺手的速查本"：打开就要看到，不输任何东西。
 * 所以 [VaultSession] 的自动锁阈值被设成 [NO_AUTO_LOCK]（等于永不锁），
 * 也不再有前后台计时。
 *
 * 代价是任何拿到解锁手机的人都能打开这个 App 看到全部账号密码 ——
 * 这和"手机记事本"完全同级，用户明确接受这个前提。
 */
class App : Application() {

    lateinit var session: VaultSession
        private set

    lateinit var gateway: VaultGateway
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 时钟仍传 elapsedRealtime：虽然现在不锁了，但会话层还有"上次活动时间"
        // 之类的记账，用单调时钟比 wall clock 稳（用户改系统时间也不会跳）。
        session = VaultSession(
            autoLockTimeoutMs = { NO_AUTO_LOCK },
            clock = { android.os.SystemClock.elapsedRealtime() }
        )
        gateway = VaultGateway(this, session)
    }

    companion object {
        lateinit var instance: App
            private set

        /**
         * 自动锁阈值：字面意义上的"永不"。
         *
         * 用 `Long.MAX_VALUE` 而不是 `0` 或负数：
         * `VaultSession` 的判断是 `now - backgroundedAt >= autoLockMs`，
         * 只有取一个永远达不到的值才安全；传 0 会导致每次回前台都立刻锁。
         * 也不引入"自动锁已关闭"的布尔开关 —— 少一个状态少一个 bug。
         */
        const val NO_AUTO_LOCK: Long = Long.MAX_VALUE
    }
}
