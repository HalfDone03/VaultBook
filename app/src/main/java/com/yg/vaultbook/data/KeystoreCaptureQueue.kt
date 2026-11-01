package com.yg.vaultbook.data

import android.content.Context
import com.yg.vaultbook.core.crypto.FieldCrypto
import com.yg.vaultbook.core.crypto.TamperedDataException
import com.yg.vaultbook.core.keystore.DeviceKeyUnavailableException
import com.yg.vaultbook.core.util.Ids
import com.yg.vaultbook.data.entity.PendingCaptureEntity
import com.yg.vaultbook.domain.CaptureInput
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/**
 * **保险库锁定时**的捕获缓冲 —— 存在独立文件里，不进主库。
 *
 * ── 为什么不能直接写 `pending_captures` 表 ──────────────────
 * 那张表在 SQLCipher 里，打开它需要 `dbKey`，而 `dbKey` 要主密码才能解封。
 * 用户点浏览器「登录」的那一刻保险库是锁着的（自动锁定 30 秒），
 * 所以捕获必然发生在"打不开主库"的时刻。
 *
 * ── 三条路，为什么选这条 ───────────────────────────────────
 *
 * | 方案 | 问题 |
 * |---|---|
 * | 锁定时不存 | 核心功能直接失效 —— 最常见的场景就是锁着的时候 |
 * | 用设备密钥包裹 dbKey，锁定时也开主库 | 攻破设备锁屏就等于攻破整个库，主密码的保护语义没了 |
 * | **本方案**：独立小文件，字段用设备密钥加密 | 只有"待确认"这一小块数据受设备密钥保护，主库钥匙始终只由主密码解封 |
 *
 * 代价写明白：本文件里的密文在设备解锁状态下可被本 App 读出（不需要主密码）。
 * 所以它只保存 **最多 [MAX_RECORDS] 条、最长 7 天** 的待确认数据；
 * 一旦用户解锁，[drain] 立刻把它们搬进主库并删除文件。
 *
 * ── 为什么文件本身不再加密一层 ────────────────────────────
 * 三个字段（标题/账号/密码）已经是密文，文件里剩下的明文只有 host 与时间戳 ——
 * 这与 `entries.host_norm` 明文存储是同一个取舍（"知道注册过哪些站点"可以接受）。
 * 再加一层文件级加密并不能减少已暴露的元数据，却多一个可能写坏的环节。
 */
class KeystoreCaptureQueue(
    context: Context,
    private val cipher: FieldCrypto,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val mutex = Any()

    /**
     * 压入一条捕获。
     *
     * @return 是否成功写入。失败（磁盘满、密钥失效）返回 false，
     *         调用方不要重试 —— 自动填充回调里重试只会拖垮系统 UI。
     */
    fun enqueue(input: CaptureInput): Boolean = synchronized(mutex) {
        val password = input.password
        if (password == null || password.isEmpty()) return false

        val now = clock()
        val id = Ids.newId()

        // 密文一律 base64 成字符串再进 JSON。
        // 直接把 ByteArray 交给 JSONObject.put 会抛异常（它只认基本类型与
        // JSONObject/JSONArray），结果就是锁定状态下的捕获全部写失败。
        val titleCt = encryptString(id, FIELD_TITLE, input.title)?.let { b64(it) }
        val usernameCt = encryptString(id, FIELD_USERNAME, input.username)?.let { b64(it) }
        val passwordCt = cipher.encrypt(id, FIELD_PASSWORD, password) ?: return false

        val entry = JSONObject().apply {
            put("id", id)
            input.hostNorm?.let { put("host", it) }
            input.packageNorm?.let { put("pkg", it) }
            put("title", titleCt ?: JSONObject.NULL)
            put("username", usernameCt ?: JSONObject.NULL)
            put("password", b64(passwordCt))
            put("conf", input.confidence.toDouble())
            put("source", input.source)
            input.matchedEntryId?.let { put("matched", it) }
            put("createdAt", now)
        }

        return try {
            val array = readArray()

            // 去重：同一来源在 5 分钟内视为同一次登录，后到的密码覆盖先到的。
            // 有域名比域名，没域名（原生 App 的登录页）比包名 —— 只比 host 的话，
            // 在 App 里每登录一次就会多出一条待确认。
            val dedupField = if (input.hostNorm != null) "host" else "pkg"
            val dedupValue = input.hostNorm ?: input.packageNorm
            var replaced = false
            if (dedupValue != null) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    if (item.optString(dedupField) != dedupValue) continue
                    val created = item.optLong("createdAt")
                    if (now - created > CaptureRepository.DEDUP_WINDOW_MS) continue
                    array.put(i, entry)
                    replaced = true
                    break
                }
            }
            if (!replaced) array.put(entry)

            writeArray(trimToCapacity(array))
            true
        } catch (e: Exception) {
            // 写盘失败不该让自动填充崩：系统会把它当成服务异常并降权
            false
        }
    }

    /**
     * 取出全部缓冲并**清空文件**。
     *
     * 返回的行可以直接 `pendingDao.upsert()` —— 密文用的 AAD 与密钥和主库里的
     * `pending_captures` 完全一致（都是 Keystore 通道 + `id|field`），无需重新加密。
     *
     * 只有解密密钥确实还在（设备密钥没被作废）时才值得搬运；
     * 如果已经解不开，直接丢，省得在主库里留一堆打不开的行。
     */
    fun drain(): List<PendingCaptureEntity> = synchronized(mutex) {
        if (!file.exists()) return emptyList()

        val rows = mutableListOf<PendingCaptureEntity>()
        try {
            val array = readArray()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                parse(item)?.let { rows += it }
            }
        } catch (e: Exception) {
            // 文件坏了：删掉重来，不能让它一直卡住后续捕获
            runCatching { file.delete() }
            return emptyList()
        }

        runCatching { file.delete() }
        rows
    }

    fun count(): Int = synchronized(mutex) {
        if (!file.exists()) return 0
        try {
            readArray().length()
        } catch (e: Exception) {
            0
        }
    }

    fun clear() {
        synchronized(mutex) { runCatching { file.delete() } }
    }

    // ── 内部 ────────────────────────────────────────────────────

    private fun parse(item: JSONObject): PendingCaptureEntity? {
        val id = item.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val createdAt = item.optLong("createdAt")

        return try {
            PendingCaptureEntity(
                id = id,
                hostNorm = item.optString("host").takeIf { it.isNotEmpty() },
                packageNorm = item.optString("pkg").takeIf { it.isNotEmpty() },
                titleCt = optBlob(item, "title"),
                usernameCt = optBlob(item, "username"),
                passwordCt = optBlob(item, "password"),
                confidence = item.optDouble("conf", 0.0).toFloat(),
                source = item.optString("source", PendingCaptureEntity.SOURCE_AUTOFILL),
                matchedEntryId = item.optString("matched").takeIf { it.isNotEmpty() },
                state = PendingCaptureEntity.STATE_PENDING,
                createdAt = createdAt,
                expiresAt = createdAt + PendingCaptureEntity.TTL_MS
            )
        } catch (e: TamperedDataException) {
            null
        } catch (e: DeviceKeyUnavailableException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * 容量上限：只保留最近的 [MAX_RECORDS] 条。
     *
     * 这是个环形缓冲 —— 用户连续登录几十个站点却一直不解锁的情况不该撑爆存储，
     * 而最早的捕获本来就最没价值（多半已经忘了当时在干什么）。
     */
    private fun trimToCapacity(array: JSONArray): JSONArray {
        if (array.length() <= MAX_RECORDS) return array

        val sorted = (0 until array.length())
            .mapNotNull { array.optJSONObject(it) }
            .sortedByDescending { it.optLong("createdAt") }
            .take(MAX_RECORDS)

        return JSONArray().apply { sorted.forEach { put(it) } }
    }

    private fun readArray(): JSONArray {
        if (!file.exists()) return JSONArray()
        val text = file.readText()
        if (text.isBlank()) return JSONArray()
        return JSONArray(text)
    }

    /**
     * 写盘：先写临时文件再 rename。
     *
     * 直接覆盖原文件的话，进程在写一半时被杀会留下一个残缺的 JSON ——
     * 下一次读取直接抛异常，用户之前所有的待确认捕获全丢。
     */
    private fun writeArray(array: JSONArray) {
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(array.toString())
            tmp.delete()
        }
    }

    private fun encryptString(id: String, field: String, value: String?): ByteArray? {
        if (value.isNullOrBlank()) return null
        val chars = value.toCharArray()
        return try {
            cipher.encrypt(id, field, chars)
        } finally {
            chars.fill('\u0000')
        }
    }

    private fun optBlob(item: JSONObject, key: String): ByteArray? {
        if (item.isNull(key)) return null
        val text = item.optString(key).takeIf { it.isNotEmpty() } ?: return null
        return Base64.getDecoder().decode(text)
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private companion object {
        const val FILE_NAME = "pending_captures.json"

        /** 与 `docs/02` 的捕获缓冲容量一致 */
        const val MAX_RECORDS = 20

        // AAD 字段名，与 pending_captures 表、entries 表保持一致
        const val FIELD_TITLE = "title"
        const val FIELD_USERNAME = "username"
        const val FIELD_PASSWORD = "password"
    }
}
