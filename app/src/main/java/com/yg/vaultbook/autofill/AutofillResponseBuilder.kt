package com.yg.vaultbook.autofill

import android.content.Context
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.service.autofill.SaveInfo
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.yg.vaultbook.R

/**
 * 一条可填的候选。密码是 `CharArray`，由调用方负责清零。
 */
data class VaultDataset(
    val entryId: String,
    val label: String,
    val sublabel: String?,
    val usernameField: ParsedField?,
    val passwordField: ParsedField?,
    val username: String?,
    val password: CharArray?
)

/**
 * 组装 `FillResponse`。
 *
 * ── 三个必须遵守的约束 ──────────────────────────────────────
 *
 * 1. **`FillResponse` 不能是空的**。`FillResponse.Builder().build()` 会抛
 *    `IllegalStateException`（必须至少有 dataset / saveInfo / authentication 之一）。
 *    没有可给的东西时要 `callback.finish(null)`，而不是交一个空壳。
 *
 * 2. **只要页面上有密码框，就必须附 `SaveInfo`** —— 即使保险库锁定、即使没有任何候选。
 *    这是本项目最关键的一条：`onSaveRequest` 只在本 service 于本次 `onFillRequest`
 *    里返回过含 `SaveInfo` 的响应时才会被系统回调。
 *    也就是说，锁定时"什么都不给"看着安全，实际后果是**用户手动输入的密码永远抓不到**，
 *    整个自动捕获功能形同虚设。
 *
 * 3. **`SaveInfo` 的 id 列表必须来自本次请求的 `AssistStructure`**，
 *    不能跨请求复用 —— `AutofillId` 只在一次会话内有效。
 *
 * ── 为什么用 RemoteViews 而不是 InlinePresentation ──────────
 * minSdk 是 26，`InlinePresentation` 要 30。用 `RemoteViews` 一套代码覆盖全部版本。
 */
class AutofillResponseBuilder(
    private val context: Context
) {

    private val packageName: String = context.packageName

    /**
     * 构造 `SaveInfo`。页面里没有密码框时返回 null（没有值得保存的东西）。
     */
    fun saveInfo(parsed: ParsedLoginForm): SaveInfo? {
        if (!parsed.hasPassword) return null

        val passwordId = parsed.passwordField?.autofillId ?: return null

        val ids = buildList {
            parsed.usernameField?.autofillId?.let { add(it) }
            add(passwordId)
        }.toTypedArray()

        var types = SaveInfo.SAVE_DATA_TYPE_PASSWORD
        if (parsed.hasUsername) {
            types = types or SaveInfo.SAVE_DATA_TYPE_USERNAME
        }

        return SaveInfo.Builder(types, ids)
            // requiredIds 已经在构造函数里给了（AutofillId[]）。
            // 不要再调 setRequiredIds —— 那个重载要的是 int[] 的 View.getId()，
            // 与 AutofillId 是两套标识，混用会编译不过或运行期错配。
            .build()
    }

    /**
     * 只带 `SaveInfo`、不带任何候选的响应 —— 锁定状态下的标准返回。
     *
     * @return null 表示连 SaveInfo 都没有（页面上没有密码框），调用方应 `finish(null)`
     */
    fun saveOnly(saveInfo: SaveInfo?): FillResponse? {
        if (saveInfo == null) return null
        return FillResponse.Builder()
            .setSaveInfo(saveInfo)
            .build()
    }

    /**
     * 带候选的响应。
     *
     * @param datasets 已按 [com.yg.vaultbook.core.matcher.SiteMatcher] 排好序
     * @param saveInfo 仍要附上 —— 用户这次可能用的是新密码，需要能捕获到
     * @return null 表示既没有可用候选也没有 SaveInfo
     */
    fun withDatasets(datasets: List<VaultDataset>, saveInfo: SaveInfo?): FillResponse? {
        val built = datasets.mapNotNull { toDataset(it) }

        if (built.isEmpty()) return saveOnly(saveInfo)

        val response = FillResponse.Builder()
        built.forEach { response.addDataset(it) }
        saveInfo?.let { response.setSaveInfo(it) }
        return response.build()
    }

    private fun toDataset(spec: VaultDataset): Dataset? {
        val presentation = presentation(spec.label, spec.sublabel)
        val builder = Dataset.Builder(presentation)

        var filled = false

        // 账号
        val usernameField = spec.usernameField
        val username = spec.username
        if (usernameField != null && !username.isNullOrEmpty()) {
            builder.setValue(
                usernameField.autofillId,
                AutofillValue.forText(username),
                presentation
            )
            filled = true
        }

        // 密码
        // 注意：AutofillValue 只接受 CharSequence，这里不得不把 CharArray 转成 String。
        // 这个 String 会立刻交给系统进程写入目标应用，本进程无法清零它 ——
        // 因此不保存引用、不打印、不放进任何字段。
        val passwordField = spec.passwordField
        val password = spec.password
        if (passwordField != null && password != null && password.isNotEmpty()) {
            builder.setValue(
                passwordField.autofillId,
                AutofillValue.forText(String(password)),
                presentation
            )
            filled = true
        }

        // 一个什么都填不上的 dataset 会让用户点了没反应，不要加进去
        return if (filled) builder.build() else null
    }

    /**
     * 候选在填充弹窗里的样子。
     *
     * 只显示标题与账号 —— **绝不显示密码**，哪怕打码也不行：
     * 这段 `RemoteViews` 会被系统拿去渲染，内容会离开本进程。
     */
    fun presentation(title: String, subtitle: String?): RemoteViews =
        RemoteViews(packageName, R.layout.autofill_dataset).apply {
            setTextViewText(R.id.autofill_title, title)
            if (subtitle.isNullOrBlank()) {
                setViewVisibility(R.id.autofill_subtitle, android.view.View.GONE)
            } else {
                setViewVisibility(R.id.autofill_subtitle, android.view.View.VISIBLE)
                setTextViewText(R.id.autofill_subtitle, subtitle)
            }
        }
}
