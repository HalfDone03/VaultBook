package com.yg.vaultbook.core.util

/**
 * 解析系统里表示"已启用的服务"的那串设置值。
 *
 * 无障碍服务是冒号分隔的一串组件名（`com.a/.Svc:com.b/com.b.T`），
 * 自动填充服务只有一个组件名，两者格式一致，所以共用一个解析。
 *
 * 组件名里的类名可能是相对写法（`.autofill.VaultAutofillService`），
 * 按 Android 的规矩要补回包名再比 —— 不补永远匹配不上，表现出来就是
 * "明明在系统里开了，应用里却显示未开启"。
 *
 * 抽成纯函数是为了能单测：真实设备上这个字段的内容各 ROM 写法不一，
 * 手上没有真机就只能靠用例覆盖几种已知形态。
 */
object EnabledServices {

    /**
     * [raw] 里是否列着 [packageName] 下的 [className] 这个服务。
     *
     * @param raw `Settings.Secure` 里的原始字符串，可能为空（从未开过）
     * @param packageName 本应用包名
     * @param className 服务的完整类名（`VaultAutofillService::class.java.name`）
     */
    fun contains(raw: String?, packageName: String, className: String): Boolean {
        if (raw.isNullOrBlank() || packageName.isEmpty() || className.isEmpty()) return false
        return raw.split(':').any { matches(it.trim(), packageName, className) }
    }

    /** 单条 `包名/类名` 是否指向目标服务。 */
    private fun matches(entry: String, packageName: String, className: String): Boolean {
        val slash = entry.indexOf('/')
        if (slash <= 0 || slash >= entry.length - 1) return false
        if (!entry.substring(0, slash).equals(packageName, ignoreCase = true)) return false
        val short = entry.substring(slash + 1)
        val full = if (short.startsWith('.')) packageName + short else short
        return full.equals(className, ignoreCase = true)
    }
}
