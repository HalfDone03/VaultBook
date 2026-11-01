package com.yg.vaultbook.data.backup

import android.content.Context
import android.net.Uri
import com.yg.vaultbook.core.crypto.KdfAlgo
import com.yg.vaultbook.core.crypto.KdfParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 备份的**文件层**：把快照写进用户用 SAF 选定的位置，或从那里读回来。
 *
 * 分成 [BackupCodec]（对象⇄字节）、[BackupCrypto]（加解密）、本类（文件 IO）
 * 三层，是为了让前两层保持纯 JVM —— 密码学与格式相关的逻辑必须能被单测毫秒级覆盖。
 * 这一层只做三件没得测的事：开流、限大小、关流。
 *
 * ── 关于 `"wt"` 模式 ────────────────────────────────────────
 * 写文件用的是 `openOutputStream(uri, "wt")`，`t` 表示截断。
 * 不能用默认的 `"w"`：SAF 目标文件可能已经存在（用户覆盖旧备份），
 * 不截断的话新内容会写在开头，旧文件多出来的尾巴留在后面 ——
 * 结果是文件长度对不上、GCM 认证失败，用户看到"备份文件已损坏"，
 * 而他刚刚才导出过。
 */
class BackupService(
    private val context: Context
) {

    companion object {
        /** 备份文件大小上限。超过基本可以判定选错了文件（比如选了个视频）。 */
        const val MAX_BACKUP_BYTES: Long = 64L * 1024 * 1024

        /** SAF 里用通用二进制类型，避免各家文件管理器把 .vbc 当成未知格式拒绝 */
        const val MIME_TYPE: String = "application/octet-stream"

        /** 建议文件名前缀，最终由用户在保存对话框里确认 */
        const val FILE_NAME_PREFIX: String = "vaultbook-backup-"

        /** 建议文件扩展名 */
        const val FILE_NAME_SUFFIX: String = ".vbc"
    }

    /**
     * 导出到 [uri]。
     *
     * @return 实际写出的字节数
     * @throws IOException 目标不可写（空间不足、用户撤回了权限）
     */
    suspend fun exportTo(
        uri: Uri,
        password: CharArray,
        snapshot: VaultSnapshot
    ): Long = withContext(Dispatchers.IO) {
        val payload = BackupCodec.encode(snapshot)
        try {
            val fileBytes = BackupCrypto.seal(payload, password)
            val out: OutputStream = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("无法写入所选位置")
            out.use { it.write(fileBytes) }
            fileBytes.size.toLong()
        } finally {
            // 明文 JSON 用完立刻抹掉。里面是全部密码的明文
            payload.fill(0)
        }
    }

    /**
     * 从 [uri] 导入。
     *
     * @throws BackupFormatException 不是本应用的备份 / 版本不支持 / 文件截断
     * @throws BackupPasswordException 备份密码不对，或文件被改动
     */
    suspend fun importFrom(uri: Uri, password: CharArray): VaultSnapshot =
        withContext(Dispatchers.IO) {
            val fileBytes = readAll(uri)
            val payload = BackupCrypto.open(fileBytes, password)
            try {
                BackupCodec.decode(payload)
            } finally {
                payload.fill(0)
            }
        }

    /**
     * 只读文件头，判断"这是不是本应用的备份"。
     *
     * 用途：用户在选文件后、输密码前，先给一句确定的提示。
     * 只读 `7 + kdfLen` 个字节，不把整个文件读进内存。
     */
    suspend fun describe(uri: Uri): BackupFileInfo = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法读取所选文件")
        input.use { ins ->
            val fixed = ByteArray(BackupFormat.FIXED_HEADER_BYTES)
            if (!readFully(ins, fixed)) {
                throw BackupFormatException("文件太小，不是有效的备份")
            }
            for (i in BackupFormat.MAGIC.indices) {
                if (fixed[i] != BackupFormat.MAGIC[i]) {
                    throw BackupFormatException("不是VaultBook的备份文件（文件头不匹配）")
                }
            }
            val version = fixed[4].toInt() and 0xFF
            if (version != BackupFormat.VERSION) {
                throw BackupFormatException(
                    "备份文件版本 $version 不受支持（本应用支持 ${BackupFormat.VERSION}）"
                )
            }
            val kdfLen = ((fixed[5].toInt() and 0xFF) shl 8) or (fixed[6].toInt() and 0xFF)
            if (kdfLen == 0 || kdfLen > BackupFormat.MAX_KDF_BYTES) {
                throw BackupFormatException("备份文件头异常（kdf 长度 $kdfLen）")
            }
            val kdfBytes = ByteArray(kdfLen)
            if (!readFully(ins, kdfBytes)) {
                throw BackupFormatException("备份文件不完整")
            }
            val params = try {
                KdfParams.decode(String(kdfBytes, Charsets.UTF_8))
            } catch (e: Exception) {
                throw BackupFormatException("备份文件的密钥参数无法解析", e)
            }
            BackupFileInfo(
                formatVersion = version,
                kdfAlgo = params.algo,
                kdfIterations = params.iterations
            )
        }
    }

    // ── 内部 ──────────────────────────────────────────────────────

    /**
     * 读整个文件，并强制大小上限。
     *
     * 上限不是为了省内存，而是为了在用户选错文件（选成视频/安装包）时
     * 给出"这不是备份文件"，而不是 OOM 崩掉。
     */
    private fun readAll(uri: Uri): ByteArray {
        val input: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法读取所选文件")
        input.use { ins ->
            val buf = ByteArrayOutputStreamLimited(MAX_BACKUP_BYTES)
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(chunk)
                if (n < 0) break
                if (n == 0) continue
                buf.write(chunk, 0, n)
            }
            return buf.toByteArray()
        }
    }

    private fun readFully(ins: InputStream, target: ByteArray): Boolean {
        var read = 0
        while (read < target.size) {
            val n = ins.read(target, read, target.size - read)
            if (n <= 0) return false
            read += n
        }
        return true
    }

    /**
     * 带上限的字节缓冲。
     *
     * 不用 `readBytes()`：它没有大小限制，选错文件会直接把进程打死。
     */
    private class ByteArrayOutputStreamLimited(private val limit: Long) {
        private val buf = java.io.ByteArrayOutputStream()
        private var total = 0L

        fun write(src: ByteArray, off: Int, len: Int) {
            total += len
            if (total > limit) {
                throw BackupFormatException(
                    "文件超过 ${limit / 1024 / 1024} MB，不是有效的备份"
                )
            }
            buf.write(src, off, len)
        }

        fun toByteArray(): ByteArray = buf.toByteArray()
    }
}

/** 备份文件头信息（不含任何凭据）。 */
data class BackupFileInfo(
    val formatVersion: Int,
    val kdfAlgo: KdfAlgo,
    val kdfIterations: Int
) {
    fun summary(): String = "备份格式 v$formatVersion，密钥派生 ${kdfAlgo.id} × $kdfIterations"
}
