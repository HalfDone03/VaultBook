package com.yg.vaultbook.data.backup

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 一行表格记录（已归一化成应用里的字段）。
 *
 * 密码用 `CharArray`，与工程其它地方保持一致：解析完、写库完立刻可以清零。
 * 但要知道一个限制：**解码 CSV 时必然先产生一个大 String**（整份文件的文本），
 * 那个 String 清不掉，只能等 GC。所以本类只保证"行对象"可清，不保证中间态。
 */
data class CsvRow(
    val title: String,
    val username: String?,
    val password: CharArray?,
    val url: String?,
    val notes: String?,
    val category: String?,
    val totp: String?
) {
    fun clearSecrets() {
        password?.fill('\u0000')
    }
}

/**
 * 解析结果。
 *
 * [columns] 是识别到的列名（无表头时是"第 1 列"这种占位），
 * 用它给用户看一句"我认成了：平台 / 账号 / 密码"，比只说"成功解析 37 行"有用得多。
 */
data class CsvParseResult(
    val rows: List<CsvRow>,
    val delimiter: Char,
    val hasHeader: Boolean,
    val skippedLines: Int,
    val encoding: String,
    val columns: List<String>
) {
    val rowCount: Int get() = rows.size

    fun clearSecrets() {
        rows.forEach { it.clearSecrets() }
    }

    /** 给 UI 直接显示的一句话。 */
    fun summary(): String = buildString {
        append("识别 ").append(rowCount).append(" 行")
        append("，分隔符 ").append(describeDelimiter(delimiter))
        append("，").append(if (hasHeader) "带表头" else "无表头（按列序）")
        append("，编码 ").append(encoding)
        if (skippedLines > 0) append("，跳过 ").append(skippedLines).append(" 行空行/无效行")
    }
}

private fun describeDelimiter(c: Char): String = when (c) {
    ',' -> "逗号"
    '\t' -> "制表符"
    ';' -> "分号"
    '|' -> "竖线"
    else -> "'$c'"
}

/**
 * 从表格文件导入账号。
 *
 * ── 为什么不用现成的 CSV 库 ──────────────────────────────────
 * 只需要读，不需要写；RFC 4180 的解析规则不到 60 行就能写完，
 * 而任何第三方库都要多背一份 dex。这个项目对体积敏感（见 docs/20）。
 *
 * ── 为什么要做"猜列名" ──────────────────────────────────────
 * 用户手里的表格是他自己（或别的密码软件）导出的，列名千奇百怪：
 * 「平台」「网站」「名称」「Title」「name」「服务」…… 让人先改表头再导入
 * 等于把麻烦推回给用户。这里给每个字段一组别名，命中即用；
 * 一个都没命中时按列序猜（第 1 列平台、第 2 列账号、第 3 列密码）。
 */
object CsvImport {

    /** 表头别名。键是内部字段，值是可能出现的列名（都已 smallcase、去空格）。 */
    private val TITLE_ALIASES = setOf(
        "平台", "网站", "站点", "名称", "标题", "服务", "应用", "app", "site",
        "title", "name", "service", "platform", "website", "account_name"
    )
    private val USERNAME_ALIASES = setOf(
        "账号", "帐号", "用户名", "用户", "邮箱", "邮件", "手机", "手机号", "登录名",
        "username", "user", "account", "email", "mail", "login", "phone", "mobile", "userid"
    )
    private val PASSWORD_ALIASES = setOf(
        "密码", "口令", "登录密码", "password", "passwd", "pwd", "pass", "secret"
    )
    private val URL_ALIASES = setOf(
        "网址", "链接", "地址", "url", "link", "website_url", "login_url", "web"
    )
    private val NOTES_ALIASES = setOf(
        "备注", "说明", "注释", "备注信息", "notes", "note", "remark", "memo", "comment", "description"
    )
    private val CATEGORY_ALIASES = setOf(
        "分类", "分组", "类别", "类型", "category", "group", "folder", "type", "tag"
    )
    private val TOTP_ALIASES = setOf(
        "totp", "otp", "2fa", "验证码", "动态码", "两步验证", "totp密钥", "otp_secret", "secret_key"
    )

    private val DELIMITER_CANDIDATES = charArrayOf(',', '\t', ';', '|')

    /**
     * 解析整份文件。
     *
     * @param bytes 文件原始字节（编码由 BOM 或内容判定）
     */
    fun parse(bytes: ByteArray): CsvParseResult {
        val (text, encoding) = decode(bytes)
        val body = stripBom(text)
        if (body.isBlank()) {
            return CsvParseResult(emptyList(), ',', false, 0, encoding, emptyList())
        }

        val delimiter = detectDelimiter(body)
        val records = splitRecords(body, delimiter)

        if (records.isEmpty()) {
            return CsvParseResult(emptyList(), delimiter, false, 0, encoding, emptyList())
        }

        val mapping = mapHeader(records.first())
        val hasHeader = mapping != null
        val dataRecords = if (hasHeader) records.drop(1) else records
        val columns = if (hasHeader) {
            records.first().map { it.trim() }
        } else {
            List(records.first().size) { "第 ${it + 1} 列" }
        }

        val rows = ArrayList<CsvRow>(dataRecords.size)
        var skipped = 0
        dataRecords.forEach { cells ->
            if (cells.all { it.isBlank() }) {
                skipped++
                return@forEach
            }
            // 表头行的列数可能和数据行不一致（表格软件常见），以数据行为准
            val row = if (hasHeader) buildFromMapping(cells, mapping!!) else buildPositional(cells)
            if (row == null) skipped++ else rows += row
        }

        return CsvParseResult(
            rows = rows,
            delimiter = delimiter,
            hasHeader = hasHeader,
            skippedLines = skipped,
            encoding = encoding,
            columns = columns
        )
    }

    // ── 解码 ──────────────────────────────────────────────────────

    /**
     * 判定编码。
     *
     * 顺序：BOM → 严格 UTF-8 → GBK。
     *
     * 为什么必须"严格"试 UTF-8：安卓上 `String(bytes, UTF_8)` 是宽松的，
     * 遇到非法字节会静默替换成 `�`，中文 GBK 文件会变成一片乱码但**不报错**。
     * 只被替换几个字符是看不出来的，用户导入完才发现密码是乱码。
     */
    private fun decode(bytes: ByteArray): Pair<String, String> {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8) to "UTF-8 BOM"
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE) to "UTF-16LE"
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE) to "UTF-16BE"
        }

        strictDecode(bytes, Charsets.UTF_8)?.let { return it to "UTF-8" }

        // Excel 在中文 Windows 上"另存为 CSV"默认就是 GBK
        val gbk = runCatching { Charset.forName("GBK") }.getOrNull()
        if (gbk != null) {
            strictDecode(bytes, gbk)?.let { return it to "GBK" }
        }
        return String(bytes, Charsets.UTF_8) to "UTF-8(宽松)"
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = try {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

    private fun stripBom(text: String): String =
        if (text.isNotEmpty() && text[0] == '\uFEFF') text.substring(1) else text

    // ── 分隔符与记录切分 ──────────────────────────────────────────

    /**
     * 猜分隔符：看前 5 个非空行里哪个候选字符出现得最多。
     *
     * 只在引号外计数 —— 备注列里写一句"地址：a,b"就会把逗号带偏。
     */
    internal fun detectDelimiter(text: String): Char {
        val counts = IntArray(DELIMITER_CANDIDATES.size)
        var lines = 0
        var inQuotes = false
        var i = 0
        while (i < text.length && lines < 5) {
            val c = text[i]
            when {
                c == '"' -> {
                    // 连续两个引号是转义，跳过
                    if (inQuotes && i + 1 < text.length && text[i + 1] == '"') i++ else inQuotes = !inQuotes
                }
                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    lines++
                }
                !inQuotes -> {
                    val idx = DELIMITER_CANDIDATES.indexOf(c)
                    if (idx >= 0) counts[idx]++
                }
            }
            i++
        }
        var best = 0
        for (k in counts.indices) if (counts[k] > counts[best]) best = k
        return DELIMITER_CANDIDATES[best]
    }

    /**
     * RFC 4180 切分：支持引号包裹、引号内换行、`""` 转义。
     *
     * 不处理"引号里出现未转义的裸引号"这种坏数据 —— 那不是 CSV，
     * 猜错比报错更糟（会把两行并成一行，用户完全看不出来）。
     */
    internal fun splitRecords(text: String, delimiter: Char): List<List<String>> {
        val records = ArrayList<List<String>>()
        var fields = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        var fieldStarted = false

        fun endField() {
            fields.add(sb.toString())
            sb.setLength(0)
            fieldStarted = false
        }

        fun endRecord() {
            endField()
            // 完全空的一行也保留：调用方要靠它把"跳过 N 行"报准。
            // 文件末尾那个换行不会多出一行 —— 收尾时只有真有内容才 flush。
            records.add(fields)
            fields = ArrayList()
        }

        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                        sb.append('"'); i++
                    }
                    c == '"' -> inQuotes = false
                    else -> sb.append(c)
                }
            } else {
                when {
                    c == '"' && !fieldStarted -> {
                        inQuotes = true; fieldStarted = true
                    }
                    c == delimiter -> endField()
                    c == '\r' -> {
                        if (i + 1 < text.length && text[i + 1] == '\n') i++
                        endRecord()
                    }
                    c == '\n' -> endRecord()
                    else -> {
                        sb.append(c); fieldStarted = true
                    }
                }
            }
            i++
        }
        if (sb.isNotEmpty() || fields.isNotEmpty()) endRecord()
        return records
    }

    // ── 表头映射 ──────────────────────────────────────────────────

    /** 列下标 +1 表示"第几列"，0 表示没有这一列。 */
    private data class HeaderMapping(
        val title: Int,
        val username: Int,
        val password: Int,
        val url: Int,
        val notes: Int,
        val category: Int,
        val totp: Int
    )

    /**
     * 判断第一行是不是表头。
     *
     * 两个条件同时成立才算：① 至少命中一个已知列名；② 没有"密码列里看着像密码"的疑点。
     * 第二条是为了防止把"平台,密码"当数据行（这种表头命中不了别名，会走列序分支）。
     */
    private fun mapHeader(cells: List<String>): HeaderMapping? {
        if (cells.isEmpty()) return null
        val normalized = cells.map { normalizeHeader(it) }
        var title = 0; var user = 0; var pwd = 0; var url = 0; var notes = 0; var cat = 0; var totp = 0
        var hits = 0
        normalized.forEachIndexed { index, name ->
            if (name.isEmpty()) return@forEachIndexed
            when {
                name in PASSWORD_ALIASES -> { pwd = index + 1; hits++ }
                name in USERNAME_ALIASES -> { user = index + 1; hits++ }
                name in TOTP_ALIASES -> { totp = index + 1; hits++ }
                name in URL_ALIASES -> { url = index + 1; hits++ }
                name in NOTES_ALIASES -> { notes = index + 1; hits++ }
                name in CATEGORY_ALIASES -> { cat = index + 1; hits++ }
                name in TITLE_ALIASES -> { title = index + 1; hits++ }
            }
        }
        if (hits == 0) return null
        // 只命中"密码"一列不算表头可能性很高（那一格可能真的写着"密码"两个字）
        if (hits == 1 && pwd > 0) return null
        return HeaderMapping(title, user, pwd, url, notes, cat, totp)
    }

    private fun normalizeHeader(raw: String): String {
        val sb = StringBuilder()
        raw.trim().lowercase().forEach { c ->
            when {
                c.isLetterOrDigit() -> sb.append(c)
                c == '_' -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun cell(cells: List<String>, index: Int): String? =
        if (index <= 0 || index > cells.size) null else cells[index - 1].trim().takeIf { it.isNotEmpty() }

    private fun buildFromMapping(cells: List<String>, m: HeaderMapping): CsvRow? {
        val title = cell(cells, m.title)
        val pwd = cell(cells, m.password)
        val url = cell(cells, m.url)
        val username = cell(cells, m.username)

        // 一个能用的条目至少要有"名字"或"密码"。两样都没有就是一行垃圾数据
        if (title == null && pwd == null) return null

        return CsvRow(
            title = title ?: url ?: username ?: "未命名",
            username = username,
            password = pwd?.toCharArray(),
            url = url,
            notes = cell(cells, m.notes),
            category = cell(cells, m.category),
            totp = cell(cells, m.totp)
        )
    }

    /**
     * 无表头时按列序猜：平台 / 账号 / 密码 / 网址 / 备注 / 分类。
     *
     * 只有两列时按「平台 / 密码」解释 —— 这正是本项目最常见的存法
     * （用户反馈"我一般存都是哪个平台 + 密码"）。三列及以上才认为第二列是账号。
     */
    private fun buildPositional(cells: List<String>): CsvRow? {
        val nonBlank = cells.map { it.trim() }
        if (nonBlank.all { it.isEmpty() }) return null

        val title = nonBlank.getOrNull(0)?.takeIf { it.isNotEmpty() }
            ?: return null
        val second = nonBlank.getOrNull(1)?.takeIf { it.isNotEmpty() }
        val third = nonBlank.getOrNull(2)?.takeIf { it.isNotEmpty() }
        val fourth = nonBlank.getOrNull(3)?.takeIf { it.isNotEmpty() }
        val fifth = nonBlank.getOrNull(4)?.takeIf { it.isNotEmpty() }
        val sixth = nonBlank.getOrNull(5)?.takeIf { it.isNotEmpty() }

        return if (nonBlank.size <= 2) {
            CsvRow(
                title = title,
                username = null,
                password = second?.toCharArray(),
                url = null,
                notes = null,
                category = null,
                totp = null
            )
        } else {
            CsvRow(
                title = title,
                username = second,
                password = third?.toCharArray(),
                url = fourth,
                notes = fifth,
                category = sixth,
                totp = null
            )
        }
    }
}
