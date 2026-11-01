package com.yg.vaultbook.data.backup

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * 备份文件的**容器格式**：一个自描述的头 + 一段 AEAD 密文。
 *
 * ```
 * 偏移   长度   内容
 * 0      4      magic "VBCK"（ASCII）
 * 4      1      格式版本（当前 1）
 * 5      2      kdf 参数长度 n（大端无符号 16 位）
 * 7      n      kdf 参数字节（UTF-8，形如 algo=pbkdf2-sha256;iter=210000;salt=...）
 * 7+n    12     GCM IV
 * 19+n   ...    密文 || 16 字节 GCM tag
 * ```
 *
 * ── 为什么 kdf 参数要放进"被认证"的头 ────────────────────────────
 * 备份文件的密钥是由**用户输入的备份密码 + 头里的 KDF 参数**共同派生的。
 * 如果头和密文分开、头不受保护，攻击者可以：
 * 1. 把 `iter=210000` 改成 `iter=1`；
 * 2. 诱导受害者用同一个备份密码再导出一个文件（或拿到任何一个用同密码导出的文件）；
 * 3. 用 1 次迭代暴力反推 —— 派生的 KEK 与原文件完全相同，但破解成本从 21 万次降到 1 次。
 *
 * 所以 [aad] 的范围是**整个头**（magic + version + kdfLen + kdf）。
 * 任何一位被改动，解密都会因为 GCM 认证失败而失败 —— 表现为"备份密码不对或文件被改过"，
 * 而不是"用错误参数解出垃圾"。
 *
 * 这个类**不依赖 Android**，纯字节操作，可以直接用 JVM 单测覆盖。
 */
object BackupFormat {

    /** "VBCK"：VaultBook baCKup */
    val MAGIC: ByteArray = byteArrayOf(0x56, 0x42, 0x43, 0x4B)

    const val VERSION: Int = 1

    const val IV_BYTES: Int = 12

    /** GCM tag 长度（字节）。备份文件必须至少够一个 tag，否则连认证都无从谈起。 */
    const val TAG_BYTES: Int = 16

    /** magic(4) + version(1) + kdfLen(2) */
    const val FIXED_HEADER_BYTES: Int = 7

    /** kdf 参数串的长度的上限。超过这个基本可以判定不是本应用的备份文件。 */
    const val MAX_KDF_BYTES: Int = 1024

    /** 最小合法文件：头（kdf 至少 1 字节）+ IV + tag */
    const val MIN_BYTES: Int = FIXED_HEADER_BYTES + 1 + IV_BYTES + TAG_BYTES

    /** 解出来的三段：KDF 参数、IV、密文（含 tag）。 */
    class Container(
        /** 派生密钥用的参数串，交给 `KdfParams.decode` 解析 */
        val kdfParams: String,
        val iv: ByteArray,
        val ciphertext: ByteArray
    )

    /**
     * 只拼出**头部字节**（magic + version + kdfLen + kdf），不含 IV 与密文。
     *
     * 单独暴露出来是因为它同时是 GCM 的 AAD（见类注释里的降级攻击）：
     * 加密时要先算出 AAD 才能 `seal`，那时还没有 IV 和密文。
     */
    fun headerBytes(kdfParams: String): ByteArray {
        require(kdfParams.isNotEmpty()) { "kdf 参数不能为空" }
        val kdfBytes = kdfParams.toByteArray(Charsets.UTF_8)
        require(kdfBytes.size <= MAX_KDF_BYTES) { "kdf 参数过长：${kdfBytes.size} 字节" }
        val out = ByteArray(FIXED_HEADER_BYTES + kdfBytes.size)
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.size)
        out[4] = VERSION.toByte()
        out[5] = ((kdfBytes.size ushr 8) and 0xFF).toByte()
        out[6] = (kdfBytes.size and 0xFF).toByte()
        System.arraycopy(kdfBytes, 0, out, FIXED_HEADER_BYTES, kdfBytes.size)
        return out
    }

    /**
     * 组装一个备份文件。
     *
     * @param kdfParams 来自 `KdfParams.encode()`，**必须**与实际派生密钥时用的是同一份
     * @param iv 加密时用的 12 字节 IV
     * @param ciphertext 密文 + tag（`Aead.seal` 的原始输出）
     */
    fun pack(kdfParams: String, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        require(iv.size == IV_BYTES) { "IV 必须是 $IV_BYTES 字节，实际 ${iv.size}" }
        require(ciphertext.size >= TAG_BYTES) { "密文至少要有 $TAG_BYTES 字节的 tag" }

        val header = headerBytes(kdfParams)
        val out = ByteArrayOutputStream(header.size + IV_BYTES + ciphertext.size)
        out.write(header)
        out.write(iv)
        out.write(ciphertext)
        return out.toByteArray()
    }

    /**
     * 拆开一个备份文件。
     * @throws BackupFormatException magic 不对、版本不认识、长度不够、kdf 长度越界
     */
    fun unpack(bytes: ByteArray): Container {
        if (bytes.size < MIN_BYTES) {
            throw BackupFormatException("文件太小（${bytes.size} 字节），不是有效的备份")
        }
        for (i in MAGIC.indices) {
            if (bytes[i] != MAGIC[i]) {
                throw BackupFormatException("不是VaultBook的备份文件（文件头不匹配）")
            }
        }
        val version = bytes[4].toInt() and 0xFF
        if (version != VERSION) {
            // 明确区分"版本更新"和"文件坏了"：前者应该提示用户升级应用，
            // 而不是让他以为密码打错了
            throw BackupFormatException(
                "备份文件版本 $version 不受支持（本应用支持 $VERSION），请升级应用后再打开"
            )
        }

        val kdfLen = ((bytes[5].toInt() and 0xFF) shl 8) or (bytes[6].toInt() and 0xFF)
        if (kdfLen == 0 || kdfLen > MAX_KDF_BYTES) {
            throw BackupFormatException("备份文件头异常（kdf 长度 $kdfLen）")
        }
        val expected = FIXED_HEADER_BYTES + kdfLen + IV_BYTES + TAG_BYTES
        if (bytes.size < expected) {
            // 只是**下限**检查：密文长度 = 明文长度 + TAG，而明文长度不固定，
            // 所以这里不能要求精确相等（早先写成 `>` 也报错，把所有正常文件都拒了）
            throw BackupFormatException("备份文件不完整（截断了 ${expected - bytes.size} 字节）")
        }

        val kdfParams = String(bytes, FIXED_HEADER_BYTES, kdfLen, Charsets.UTF_8)
        if (kdfParams.isBlank()) {
            throw BackupFormatException("备份文件头异常（kdf 参数为空）")
        }

        val ivStart = FIXED_HEADER_BYTES + kdfLen
        val iv = bytes.copyOfRange(ivStart, ivStart + IV_BYTES)
        val ciphertext = bytes.copyOfRange(ivStart + IV_BYTES, bytes.size)
        return Container(kdfParams, iv, ciphertext)
    }

    /**
     * 取出认证范围（AAD）：整个头。
     *
     * 调用方拿它去 `Aead.seal/open(..., aad = BackupFormat.aad(bytes), ...)`。
     * 注意要在 [unpack] **之前**从原始字节里切，因为 [unpack] 只返回拆好的三段。
     */
    fun aad(bytes: ByteArray): ByteArray {
        if (bytes.size < FIXED_HEADER_BYTES) {
            throw BackupFormatException("文件太小，读不出文件头")
        }
        val kdfLen = ((bytes[5].toInt() and 0xFF) shl 8) or (bytes[6].toInt() and 0xFF)
        val end = FIXED_HEADER_BYTES + kdfLen
        if (kdfLen == 0 || kdfLen > MAX_KDF_BYTES || bytes.size < end) {
            throw BackupFormatException("备份文件头异常（kdf 长度 $kdfLen）")
        }
        return bytes.copyOfRange(0, end)
    }

    /**
     * 判断"这段字节看起来像不像本应用的备份"。
     *
     * 用于导入选择文件后**先给一句人话提示**，而不是等解到一半才报格式错。
     * 只看 magic，不做完整性校验 —— 真正的校验在 [unpack] 与 GCM 认证那里。
     */
    fun looksLikeBackup(bytes: ByteArray): Boolean {
        if (bytes.size < FIXED_HEADER_BYTES) return false
        return MAGIC.indices.all { bytes[it] == MAGIC[it] }
    }

    /** 供测试与调试：把头部按结构打印出来（不含密文）。 */
    fun describeHeader(bytes: ByteArray): String {
        val c = unpack(bytes)
        return buildString {
            append("VBCK v").append(VERSION)
            append(" kdf=").append(c.kdfParams)
            append(" iv=").append(c.iv.size).append("B")
            append(" ct=").append(c.ciphertext.size).append("B")
        }
    }

    /** 小工具：给测试和排错用，把字节转成定长十六进制。 */
    fun hex(bytes: ByteArray, limit: Int = 16): String {
        val n = minOf(bytes.size, limit)
        val sb = StringBuilder(n * 3)
        for (i in 0 until n) {
            sb.append(String.format("%02x", bytes[i]))
            if (i != n - 1) sb.append(' ')
        }
        if (bytes.size > limit) sb.append(" … (+${bytes.size - limit}B)")
        return sb.toString()
    }

    /** 读大端 16 位。留着给以后加"条目数"之类的头字段用，也方便测试断言。 */
    fun readUInt16(bytes: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 1 < bytes.size) { "越界读 uint16 @ $offset" }
        val buf = ByteBuffer.wrap(bytes, offset, 2)
        return buf.short.toInt() and 0xFFFF
    }
}

/**
 * 备份文件格式错误（**不是**密码错）。
 *
 * 与 `WrongPasswordException` 分开，因为 UI 提示完全不同：
 * 格式错是"你选错文件了 / 文件坏了"，密码错是"密码记错了"。
 * 混成一个会让用户反复试密码，而正确做法是换一个文件。
 */
class BackupFormatException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
