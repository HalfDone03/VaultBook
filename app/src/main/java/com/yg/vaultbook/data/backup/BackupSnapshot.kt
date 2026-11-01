package com.yg.vaultbook.data.backup

/**
 * 备份的**明文**结构。
 *
 * 这些对象只在"导出/导入"那一刻存在于内存里，落盘前一定会被
 * `BackupCrypto` 整体加密成 `VBCK` 容器。所以这里的字段都是明文
 * （相比于数据库里逐字段加密的实体）。
 *
 * ── 关于密码用 CharArray ─────────────────────────────────────────
 * 与工程其它地方一致，密码用 `CharArray` 而不是 `String`，用完可以 `fill('\u0000')`。
 * 但要说清一个做不到的事：**JSON 序列化过程中必然产生 String**
 * （`org.json` 只接受 `String`），那个 String 无法清零，只能靠 GC 回收。
 * 这与剪贴板是同一类限制，属于"平台没有提供安全字符串"的硬约束。
 * 能做到的是：不把它写进日志、不长期持有引用、导出后立刻调 [clearSecrets]。
 */
data class SnapshotField(
    val id: String,
    val label: String,
    val value: String,
    /** text | secret | multiline */
    val type: String,
    val sort: Int
)

/** 一条完整条目（含密码）。 */
data class SnapshotEntry(
    val id: String,
    val categoryId: String?,
    val title: String,
    val username: String?,
    val password: CharArray?,
    val url: String?,
    val notes: String?,
    val totp: String?,
    val favorite: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val pwdUpdatedAt: Long?,
    val customFields: List<SnapshotField>,
    /**
     * 归一化后的域名与包名。
     *
     * **必须原样备份**，不能从 [url] 重算：
     * 从原生 App 捕获到的条目没有 url，只有 packageNorm。
     * 一旦这个字段丢了，恢复之后这些条目就再也不会被自动填充匹配到 ——
     * 而用户是在"换了手机、想恢复"的时候才发现，那时已经没法补救了。
     */
    val hostNorm: String? = null,
    val packageNorm: String? = null,
    /** 影响自动填充的候选排序（最近用过、用过几次），一并带走 */
    val lastUsedAt: Long? = null,
    val useCount: Int = 0,
    /** 非 null = 这条在回收站里。备份要能完整还原，包括"删了但还没到期"的状态 */
    val deletedAt: Long? = null
) {
    /** 导出/导入结束后必须调用。 */
    fun clearSecrets() {
        password?.fill('\u0000')
    }
}

data class SnapshotCategory(
    val id: String,
    val name: String,
    val icon: String?,
    val color: String?,
    val sort: Int,
    val builtin: Boolean
)

/**
 * 一次备份的完整内容。
 *
 * 之所以连**分类**一起备份：条目的 `category_id` 是外键，只导条目会导致
 * 导回来全是"未分类"。而分类的 `id` 参与名称密文的 AAD，所以必须原样搬。
 */
data class VaultSnapshot(
    val createdAt: Long,
    val categories: List<SnapshotCategory>,
    val entries: List<SnapshotEntry>
) {
    val entryCount: Int get() = entries.size

    fun clearSecrets() {
        entries.forEach { it.clearSecrets() }
    }
}
