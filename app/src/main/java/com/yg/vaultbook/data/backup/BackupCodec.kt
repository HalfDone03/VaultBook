package com.yg.vaultbook.data.backup

import org.json.JSONArray
import org.json.JSONObject

/**
 * 备份内容的 JSON 编解码（明文层）。
 *
 * 这一层只负责「对象 ⇄ 字节」，不做任何加密。加密在 [BackupCrypto]，
 * 容器头在 [BackupFormat]，三者串起来才是完整链路：
 *
 * ```
 * VaultSnapshot --encode--> JSON 字节 --BackupCrypto.open/seal--> VBCK 文件
 * ```
 *
 * ── 为什么不用 protobuf / 手写二进制 ────────────────────────────
 * 备份是**长期资产**：用户可能三年后才拿它来恢复。JSON 的好处是
 * 万一本应用不在了，用记事本 + 任何脚本都能把内容捞出来。
 * 加密已经保证了机密性，格式的可读性反而是优点。
 *
 * 解析一律用 `opt*` 且给默认值：旧版本导出的文件被新版本读到时，
 * 新增字段缺失不应该让整份备份作废。
 */
object BackupCodec {

    /** 明文 JSON 的版本。与容器版本（[BackupFormat.VERSION]）独立演进。 */
    const val PAYLOAD_VERSION: Int = 1

    // ── 编码 ──────────────────────────────────────────────────────

    fun encode(snapshot: VaultSnapshot): ByteArray = encodeToJson(snapshot).toString().toByteArray(Charsets.UTF_8)

    fun encodeToJson(snapshot: VaultSnapshot): JSONObject {
        val root = JSONObject()
        root.put("v", PAYLOAD_VERSION)
        root.put("createdAt", snapshot.createdAt)

        val cats = JSONArray()
        snapshot.categories.forEach { c ->
            cats.put(
                JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                    putOrNull("icon", c.icon)
                    putOrNull("color", c.color)
                    put("sort", c.sort)
                    put("builtin", c.builtin)
                }
            )
        }
        root.put("categories", cats)

        val entries = JSONArray()
        snapshot.entries.forEach { e ->
            entries.put(
                JSONObject().apply {
                    put("id", e.id)
                    putOrNull("categoryId", e.categoryId)
                    put("title", e.title)
                    putOrNull("username", e.username)
                    // 密码在这里必然变成 String —— 见 BackupSnapshot 的类注释，
                    // 这是 org.json 的限制，无法避免
                    putOrNull("password", e.password?.let { String(it) })
                    putOrNull("url", e.url)
                    putOrNull("notes", e.notes)
                    putOrNull("totp", e.totp)
                    put("favorite", e.favorite)
                    put("createdAt", e.createdAt)
                    put("updatedAt", e.updatedAt)
                    putOrNull("pwdUpdatedAt", e.pwdUpdatedAt)
                    putOrNull("hostNorm", e.hostNorm)
                    putOrNull("packageNorm", e.packageNorm)
                    putOrNull("lastUsedAt", e.lastUsedAt)
                    put("useCount", e.useCount)
                    putOrNull("deletedAt", e.deletedAt)
                    put("fields", JSONArray().also { arr ->
                        e.customFields.forEach { f ->
                            arr.put(
                                JSONObject().apply {
                                    put("id", f.id)
                                    put("label", f.label)
                                    put("value", f.value)
                                    put("type", f.type)
                                    put("sort", f.sort)
                                }
                            )
                        }
                    })
                }
            )
        }
        root.put("entries", entries)
        return root
    }

    // ── 解码 ──────────────────────────────────────────────────────

    /**
     * @throws BackupFormatException JSON 结构不对（不是本应用的备份，或内容被截断）
     */
    fun decode(bytes: ByteArray): VaultSnapshot {
        val text = String(bytes, Charsets.UTF_8)
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw BackupFormatException("备份内容不是有效的 JSON", e)
        }
        return decodeFromJson(root)
    }

    fun decodeFromJson(root: JSONObject): VaultSnapshot {
        val version = root.optInt("v", 0)
        if (version <= 0) {
            throw BackupFormatException("备份内容缺少版本号")
        }
        if (version > PAYLOAD_VERSION) {
            throw BackupFormatException("备份内容版本 $version 高于本应用支持的 $PAYLOAD_VERSION，请升级应用")
        }

        val categories = mutableListOf<SnapshotCategory>()
        root.optJSONArray("categories")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optText("id") ?: continue
                categories += SnapshotCategory(
                    id = id,
                    name = o.optText("name").orEmpty(),
                    icon = o.optText("icon"),
                    color = o.optText("color"),
                    sort = o.optInt("sort", 0),
                    builtin = o.optBoolean("builtin", false)
                )
            }
        }

        val entries = mutableListOf<SnapshotEntry>()
        root.optJSONArray("entries")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optText("id") ?: continue

                val fields = mutableListOf<SnapshotField>()
                o.optJSONArray("fields")?.let { fArr ->
                    for (j in 0 until fArr.length()) {
                        val fo = fArr.optJSONObject(j) ?: continue
                        fields += SnapshotField(
                            id = fo.optText("id") ?: continue,
                            label = fo.optText("label").orEmpty(),
                            value = fo.optText("value").orEmpty(),
                            type = fo.optText("type") ?: "text",
                            sort = fo.optInt("sort", j)
                        )
                    }
                }

                entries += SnapshotEntry(
                    id = id,
                    categoryId = o.optText("categoryId"),
                    title = o.optText("title").orEmpty().ifBlank { "未命名" },
                    username = o.optText("username"),
                    // 空字符串按"没有密码"处理，而不是"密码是空串"——
                    // 两者在业务上等价，但前者能省掉一次无意义的加密写入
                    password = o.optText("password")?.takeIf { it.isNotEmpty() }?.toCharArray(),
                    url = o.optText("url"),
                    notes = o.optText("notes"),
                    totp = o.optText("totp"),
                    favorite = o.optBoolean("favorite", false),
                    createdAt = o.optLong("createdAt", 0L),
                    updatedAt = o.optLong("updatedAt", 0L),
                    pwdUpdatedAt = if (o.isNull("pwdUpdatedAt")) null else o.optLong("pwdUpdatedAt", 0L),
                    customFields = fields,
                    hostNorm = o.optText("hostNorm"),
                    packageNorm = o.optText("packageNorm"),
                    lastUsedAt = if (o.isNull("lastUsedAt")) null else o.optLong("lastUsedAt", 0L),
                    useCount = o.optInt("useCount", 0),
                    deletedAt = if (o.isNull("deletedAt")) null else o.optLong("deletedAt", 0L)
                )
            }
        }

        return VaultSnapshot(
            createdAt = root.optLong("createdAt", 0L),
            categories = categories,
            entries = entries
        )
    }

    // ── 小工具 ────────────────────────────────────────────────────

    /**
     * 取一个"可能不存在"的文本字段。
     *
     * `optString` 遇到 `JSONObject.NULL` 会返回 `"null"` 这个字符串（org.json 的历史包袱），
     * 所以必须先 `isNull` 判断。空串也当没有 —— 数据库里用 null 表示"未填写"，
     * 不要把 "" 和 null 在导入导出时互相污染。
     */
    private fun JSONObject.optText(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val s = optString(key)
        return s.takeIf { it.isNotEmpty() }
    }

    private fun JSONObject.putOrNull(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }
}
