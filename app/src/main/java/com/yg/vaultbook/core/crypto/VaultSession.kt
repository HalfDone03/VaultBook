package com.yg.vaultbook.core.crypto

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VaultState {
    LOCKED,
    UNLOCKED
}

/**
 * 全局保险库会话：整个 App 里唯一持有明文密钥的地方。
 *
 * 生命周期规则（与 docs/02 §3.4 对应）：
 * - 解锁：注入 [VaultKeys]，构造 [FieldCipher]，状态转 UNLOCKED
 * - 锁定：销毁密钥 → 清空 cipher → 状态转 LOCKED。**任何 UI 组件的 onDestroy 都不锁**，
 *   锁只由"退到后台超时""用户手动锁""异常"触发
 * - 退到后台开始计时，回到前台若超过 `autoLockTimeoutMs`(默认 30s) 则立即锁定
 *
 * [clock] 与 [autoLockTimeoutMs] 都可注入，便于单测不用真的 sleep 30 秒。
 * 默认用单调时钟 `System.nanoTime()`，避免系统时间被改动导致锁定失效。
 */
class VaultSession(
    private val autoLockTimeoutMs: () -> Long = { DEFAULT_AUTO_LOCK_MS },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) {

    private var keys: VaultKeys? = null
    private var cipher: FieldCipher? = null

    private val _state = MutableStateFlow(VaultState.LOCKED)
    val state: StateFlow<VaultState> = _state.asStateFlow()

    private var backgroundedAt: Long = NOT_BACKGROUNDED

    val isUnlocked: Boolean get() = keys != null

    /** 触发自动锁定的后台时长（毫秒），供设置页展示 */
    val autoLockMs: Long get() = autoLockTimeoutMs()

    /**
     * 注入已解锁的密钥。
     * 若会话里已有一份密钥（理论上不该发生），先销毁旧的，避免出现两份有效密钥。
     */
    fun unlock(keys: VaultKeys) {
        this.keys?.destroy()
        this.keys = keys
        this.cipher = FieldCipher(keys.dek)
        this.backgroundedAt = NOT_BACKGROUNDED
        _state.value = VaultState.UNLOCKED
    }

    /** 锁定并销毁密钥。重复调用安全。 */
    fun lock() {
        keys?.destroy()
        keys = null
        cipher = null
        backgroundedAt = NOT_BACKGROUNDED
        _state.value = VaultState.LOCKED
    }

    /**
     * 取字段加密器。锁定状态下调用会抛异常 —— 这是刻意的：
     * 让"忘记先解锁"在开发期立即暴露，而不是静默写入失败。
     */
    fun requireCipher(): FieldCipher =
        cipher ?: throw IllegalStateException("保险库已锁定，无法访问字段加密器")

    fun keysOrNull(): VaultKeys? = keys

    /** 进程退到后台时调用，开始计时 */
    fun onEnterBackground(now: Long = clock()) {
        if (isUnlocked) backgroundedAt = now
    }

    /**
     * 回到前台时调用。
     * @return true 表示因超时已被锁定，UI 应跳转到解锁页
     */
    fun onEnterForeground(now: Long = clock()): Boolean {
        if (!isUnlocked) return false
        val since = backgroundedAt
        backgroundedAt = NOT_BACKGROUNDED
        if (since == NOT_BACKGROUNDED) return false

        if (now - since >= autoLockTimeoutMs()) {
            lock()
            return true
        }
        return false
    }

    companion object {
        /** 文档阈值：后台停留 30 秒即自动锁定 */
        const val DEFAULT_AUTO_LOCK_MS: Long = 30_000L

        private const val NOT_BACKGROUNDED: Long = -1L
    }
}
