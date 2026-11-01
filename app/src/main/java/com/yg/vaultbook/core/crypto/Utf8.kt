package com.yg.vaultbook.core.crypto

/**
 * 极简 UTF-8 编解码器。
 *
 * 为什么不用 `String(bytes, UTF_8)` / `String(chars)`：
 * 一旦构造 String，字符会驻留在一个不可主动擦除的堆对象里，直到 GC 回收。
 * 堆转储、崩溃 dump、甚至某些 ROM 的 debug 日志都可能把它捞出来。
 * 密码路径必须绕开 String：全程 CharArray <-> ByteArray，用完立即 fill 清零。
 *
 * 支持范围：完整 UTF-8（1–4 字节），代理对正确合并为 4 字节序列；
 * 孤立代理与非法字节序列替换为 U+FFFD，不抛异常（解密数据的容错优先）。
 *
 * 注意：本类的 encode/decode 不是通用文本工具，仅供 FieldCipher 内部使用。
 */
object Utf8 {

    private const val REPLACEMENT = 0xFFFD

    fun encode(chars: CharArray): ByteArray {
        if (chars.isEmpty()) return ByteArray(0)

        // BMP 字符最多 3 字节，代理对 2 个 char 输出 4 字节，故 3 * size 是安全上界
        val out = ByteArray(chars.size * 3 + 4)
        var p = 0
        var i = 0

        while (i < chars.size) {
            val c = chars[i].code
            when {
                c < 0x80 -> out[p++] = c.toByte()

                c < 0x800 -> {
                    out[p++] = (0xC0 or (c shr 6)).toByte()
                    out[p++] = (0x80 or (c and 0x3F)).toByte()
                }

                isHighSurrogate(c) && i + 1 < chars.size && isLowSurrogate(chars[i + 1].code) -> {
                    val cp = Character.toCodePoint(chars[i], chars[i + 1])
                    out[p++] = (0xF0 or (cp shr 18)).toByte()
                    out[p++] = (0x80 or ((cp shr 12) and 0x3F)).toByte()
                    out[p++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                    out[p++] = (0x80 or (cp and 0x3F)).toByte()
                    i++
                }

                // BMP 其余部分（U+0800..U+FFFF）：3 字节。
                // 中文、日文、全角符号、变体选择符 U+FE0F 全都在这一段 ——
                // 漏掉这个分支的后果是**任何中文密码/备注都被写成 U+FFFD**，
                // 存进去就再也拿不回来（这一条是被真实单测抓出来的）。
                c < 0x10000 && !isHighSurrogate(c) && !isLowSurrogate(c) -> {
                    out[p++] = (0xE0 or (c shr 12)).toByte()
                    out[p++] = (0x80 or ((c shr 6) and 0x3F)).toByte()
                    out[p++] = (0x80 or (c and 0x3F)).toByte()
                }

                else -> {
                    // 孤立代理：不是合法 UTF-8，写 U+FFFD
                    out[p++] = (0xE0 or (REPLACEMENT shr 12)).toByte()
                    out[p++] = (0x80 or ((REPLACEMENT shr 6) and 0x3F)).toByte()
                    out[p++] = (0x80 or (REPLACEMENT and 0x3F)).toByte()
                }
            }
            i++
        }

        return out.copyOf(p).also { out.fill(0) }
    }

    fun decode(bytes: ByteArray): CharArray {
        if (bytes.isEmpty()) return CharArray(0)

        // 每个字节最多产出 1 个 char（4 字节序列产出 2 个 char），bytes.size 是安全上界
        val out = CharArray(bytes.size)
        var p = 0
        var i = 0

        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            when {
                b0 < 0x80 -> {
                    out[p++] = b0.toChar()
                    i += 1
                }

                (b0 and 0xE0) == 0xC0 && i + 1 < bytes.size -> {
                    val b1 = bytes[i + 1].toInt() and 0xFF
                    out[p++] = (((b0 and 0x1F) shl 6) or (b1 and 0x3F)).toChar()
                    i += 2
                }

                (b0 and 0xF0) == 0xE0 && i + 2 < bytes.size -> {
                    val b1 = bytes[i + 1].toInt() and 0xFF
                    val b2 = bytes[i + 2].toInt() and 0xFF
                    out[p++] = (((b0 and 0x0F) shl 12) or
                            ((b1 and 0x3F) shl 6) or
                            (b2 and 0x3F)).toChar()
                    i += 3
                }

                (b0 and 0xF8) == 0xF0 && i + 3 < bytes.size -> {
                    val b1 = bytes[i + 1].toInt() and 0xFF
                    val b2 = bytes[i + 2].toInt() and 0xFF
                    val b3 = bytes[i + 3].toInt() and 0xFF
                    val cp = ((b0 and 0x07) shl 18) or
                            ((b1 and 0x3F) shl 12) or
                            ((b2 and 0x3F) shl 6) or
                            (b3 and 0x3F)
                    val v = cp - 0x10000
                    out[p++] = (0xD800 + (v shr 10)).toChar()
                    if (p < out.size) {
                        out[p++] = (0xDC00 + (v and 0x3FF)).toChar()
                    }
                    i += 4
                }

                else -> {
                    out[p++] = REPLACEMENT.toChar()
                    i += 1
                }
            }
        }

        val result = out.copyOf(p)
        out.fill('\u0000')
        return result
    }

    private fun isHighSurrogate(c: Int): Boolean = c in 0xD800..0xDBFF

    private fun isLowSurrogate(c: Int): Boolean = c in 0xDC00..0xDFFF
}
