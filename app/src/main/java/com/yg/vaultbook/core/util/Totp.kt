package com.yg.vaultbook.core.util

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
/**
 * TOTP（RFC 6238，基于 HOTP RFC 4226）。
 *
 * 为什么自己写而不引第三方库：这段算法一共不到 120 行，公式写死在 RFC 里，
 * 引入一个依赖反而多一处供应链风险 —— 而它处理的正是"第二因素密钥"。
 *
 * 时间单位约定：对外接口全部收**毫秒**（`System.currentTimeMillis()`），
 * 内部换算成 30 秒的计数器。唯一例外是 [codeAt]，它直接收 Unix 秒，
 * 存在的理由是测试里要对着 RFC 向量逐个比对。
 *
 * 安全约定：`secret` 是密钥，调用方负责在用完后 `fill(0)`。
 * 本类不缓存、不复制、不打印它。
 */
object Totp {

    const val DEFAULT_DIGITS = 6
    const val DEFAULT_PERIOD_SECONDS = 30

    /** 支持的算法。otpauth URI 里 SHA1 是默认值（也最常见）。 */
    enum class Algorithm(val macName: String) {
        SHA1("HmacSHA1"),
        SHA256("HmacSHA256"),
        SHA512("HmacSHA512"),
    }

    /**
     * @param secret Base32 解码后的原始密钥字节
     */
    class Config(
        val secret: ByteArray,
        val digits: Int = DEFAULT_DIGITS,
        val periodSeconds: Int = DEFAULT_PERIOD_SECONDS,
        val algorithm: Algorithm = Algorithm.SHA1,
    ) {
        init {
            require(secret.isNotEmpty()) { "TOTP 密钥不能为空" }
            // RFC 6238 允许 6~8 位；再长的没有实现支持，"位数少"才是风险
            require(digits in 6..8) { "TOTP 位数只支持 6~8" }
            require(periodSeconds in 1..3600) { "TOTP 周期必须在 1~3600 秒之间" }
        }
    }

    /** otpauth:// URI 解析结果。 */
    data class OtpAuthUri(
        val secretBase32: String,
        val issuer: String?,
        val account: String?,
        val digits: Int = DEFAULT_DIGITS,
        val periodSeconds: Int = DEFAULT_PERIOD_SECONDS,
        val algorithm: Algorithm = Algorithm.SHA1,
    )

    // ── Base32（RFC 4648）───────────────────────────────────────────────

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /**
     * 解码 Base32 文本。
     *
     * 宽松处理是刻意的：用户从网页复制密钥时经常带着空格、连字符、小写，
     * 甚至末尾的 `=` 数量不对。这些都按等价处理。
     *
     * @return null 表示含非法字符（用户输错了，而不是"格式不规范"）
     */
    fun parseBase32(input: String?): ByteArray? {
        if (input.isNullOrBlank()) return null

        // 归一：去掉空格 / 连字符 / 等号，转大写
        val cleaned = StringBuilder(input.length)
        for (ch in input) {
            when {
                ch == ' ' || ch == '-' || ch == '_' || ch == '=' -> Unit
                ch.isWhitespace() -> Unit
                else -> cleaned.append(ch.uppercaseChar())
            }
        }
        if (cleaned.isEmpty()) return null

        val out = ByteArray((cleaned.length * 5) / 8)
        var buffer = 0
        var bits = 0
        var written = 0

        for (ch in cleaned) {
            val value = ALPHABET.indexOf(ch)
            if (value < 0) return null
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                if (written < out.size) {
                    out[written++] = ((buffer shr bits) and 0xFF).toByte()
                }
            }
        }
        // 最后一个字节可能没凑满（长度不是 8 的倍数），残余 bit 丢弃是标准行为
        return if (written == 0) null else out.copyOf(written)
    }

    // ── otpauth:// URI ─────────────────────────────────────────────────

    /**
     * 解析 `otpauth://totp/Issuer:account?secret=...&digits=6&period=30&algorithm=SHA1`。
     *
     * 只认 `totp`，`hotp`（计数器型）返回 null —— 那种需要用户手动提供计数器，
     * UI 上无法自解释，宁可不支持也不要给出错的码。
     */
    fun parseOtpAuthUri(uri: String?): OtpAuthUri? {
        if (uri.isNullOrBlank()) return null
        val trimmed = uri.trim()
        if (!trimmed.startsWith("otpauth://", ignoreCase = true)) return null

        val withoutScheme = trimmed.substring("otpauth://".length)
        val slash = withoutScheme.indexOf('/')
        if (slash <= 0) return null
        val type = withoutScheme.substring(0, slash)
        if (!type.equals("totp", ignoreCase = true)) return null

        val rest = withoutScheme.substring(slash + 1)
        val queryIndex = rest.indexOf('?')
        val label = if (queryIndex >= 0) rest.substring(0, queryIndex) else rest
        val query = if (queryIndex >= 0) rest.substring(queryIndex + 1) else ""

        val params = HashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val key = pair.substring(0, eq)
            val value = pair.substring(eq + 1)
            params[key] = urlDecode(value)
        }

        val secret = params["secret"]?.takeIf { it.isNotBlank() } ?: return null

        // label 形如 "Issuer:account"，冒号可能是 URL 编码的 %3A
        val decodedLabel = urlDecode(label)
        val issuer: String?
        val account: String?
        val colon = decodedLabel.indexOf(':')
        if (colon >= 0) {
            issuer = decodedLabel.substring(0, colon).trim().takeIf { it.isNotEmpty() }
            account = decodedLabel.substring(colon + 1).trim().takeIf { it.isNotEmpty() }
        } else {
            issuer = params["issuer"]?.trim()?.takeIf { it.isNotEmpty() }
            account = decodedLabel.trim().takeIf { it.isNotEmpty() }
        }
        val finalIssuer = issuer ?: params["issuer"]?.trim()?.takeIf { it.isNotEmpty() }

        val digits = params["digits"]?.toIntOrNull()?.takeIf { it in 6..8 } ?: DEFAULT_DIGITS
        val period = params["period"]?.toIntOrNull()?.takeIf { it in 1..3600 }
            ?: DEFAULT_PERIOD_SECONDS
        val algorithm = when (params["algorithm"]?.uppercase()) {
            "SHA256" -> Algorithm.SHA256
            "SHA512" -> Algorithm.SHA512
            else -> Algorithm.SHA1
        }

        return OtpAuthUri(
            secretBase32 = secret,
            issuer = finalIssuer,
            account = account,
            digits = digits,
            periodSeconds = period,
            algorithm = algorithm,
        )
    }

    /**
     * 只做足够的百分号解码。用 `java.net.URLDecoder` 会把 `+` 解成空格，
     * 而 otpauth 的 query 里 `+` 在部分实现中就是字面量。
     */
    private fun urlDecode(raw: String): String {
        if (!raw.contains('%')) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '%' && i + 2 < raw.length) {
                val hex = raw.substring(i + 1, i + 3)
                val value = hex.toIntOrNull(16)
                if (value != null) {
                    out.append(value.toChar())
                    i += 3
                    continue
                }
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    // ── 核心 ───────────────────────────────────────────────────────────

    /** 当前时刻的计数器（`floor(unixSeconds / period)`）。 */
    fun counterAt(periodSeconds: Int, timestampMs: Long): Long =
        Math.floorDiv(timestampMs / 1000L, periodSeconds.toLong())

    /** 当前码还剩几秒失效（1..period）。用于进度条。 */
    fun remainingSeconds(periodSeconds: Int, timestampMs: Long): Int {
        val elapsed = Math.floorMod(timestampMs / 1000L, periodSeconds.toLong()).toInt()
        return periodSeconds - elapsed
    }

    /** 当前时刻的验证码。 */
    fun code(config: Config, timestampMs: Long): String =
        codeAt(config, timestampMs / 1000L)

    /**
     * 指定 Unix 秒的验证码。测试用 RFC 向量比对时走这个。
     *
     * 动态截断（RFC 4226 §5.3）：取 HMAC 最后一个字节的低 4 bit 作为偏移量，
     * 从该处读 4 字节并抹掉最高位，再对 10^digits 取模。
     */
    fun codeAt(config: Config, unixSeconds: Long): String {
        val counter = Math.floorDiv(unixSeconds, config.periodSeconds.toLong())
        val mac = Mac.getInstance(config.algorithm.macName)
        mac.init(SecretKeySpec(config.secret, config.algorithm.macName))
        val message = ByteArray(8)
        var value = counter
        for (i in 7 downTo 0) {
            message[i] = (value and 0xFF).toByte()
            value = value ushr 8
        }
        val hash = try {
            mac.doFinal(message)
        } finally {
            message.fill(0)
            // Mac 实例用完即弃；SecretKeySpec 内部持有 secret 的副本无法清零，
            // 这是 JCE 的限制，靠"不缓存 config、用完由调用方清 secret"兜住。
        }

        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)

        val modulus = POW10[config.digits]
        val truncated = binary % modulus
        return truncated.toString().padStart(config.digits, '0')
    }

    private val POW10 = intArrayOf(1, 10, 100, 1_000, 10_000, 100_000, 1_000_000,
        10_000_000, 100_000_000)

    /**
     * 把用户录入的文本解析成 [Config]。返回 null 表示"这不是有效的 TOTP 密钥"，
     * 调用方应当**当作没有填写**处理，而不是弹错误框 —— 用户可能只是随手把
     * 一段备用码写进了这个字段。
     *
     * 接受两种写法，因为用户拿到密钥的途径就这两种：
     * - 完整的 `otpauth://totp/...`（网页上点「无法扫描」复制出来的就是这一整串）
     * - 纯 Base32 文本（空格、连字符、小写、缺 padding 都能认）
     */
    fun parseConfig(input: String?): Config? {
        val raw = input?.trim().orEmpty()
        if (raw.isEmpty()) return null

        val uri = parseOtpAuthUri(raw)
        if (uri != null) {
            val secret = parseBase32(uri.secretBase32) ?: return null
            return runCatching {
                Config(
                    secret = secret,
                    digits = uri.digits,
                    periodSeconds = uri.periodSeconds,
                    algorithm = uri.algorithm
                )
            }.getOrElse {
                secret.fill(0)
                null
            }
        }

        val secret = parseBase32(raw) ?: return null
        return runCatching { Config(secret = secret) }.getOrElse {
            secret.fill(0)
            null
        }
    }

    /** 把 6 位码显示成 `123 456`，一眼能念出来。 */
    fun format(code: String): String {
        if (code.length <= 3) return code
        val half = (code.length + 1) / 2
        return code.substring(0, half) + " " + code.substring(half)
    }
}
