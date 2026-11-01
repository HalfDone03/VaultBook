package com.yg.vaultbook.data.backup

import com.yg.vaultbook.core.crypto.Aead
import com.yg.vaultbook.core.crypto.CryptoBlob
import com.yg.vaultbook.core.crypto.Kdf
import com.yg.vaultbook.core.crypto.KdfParams
import com.yg.vaultbook.core.crypto.TamperedDataException

/**
 * 备份文件的**加解密**：把一个 JSON 字节串变成可落盘的 `VBCK` 文件，或反过来。
 *
 * 密钥来自**用户单独输入的备份密码**（不是主密码）。理由：
 * - 用户可能想把备份放到网盘，此时不该让它跟主密码产生任何关联；
 * - 换主密码不应该让旧备份失效；
 * - 反过来也一样：备份密码泄露不等于主密码泄露。
 *
 * 代价是用户要记两个密码。这一点在导出页面上必须写清楚 ——
 * 备份密码丢了，备份文件就是一堆字节，没有任何后门。
 *
 * ── 链路 ────────────────────────────────────────────────────
 * ```
 * seal:  明文 JSON ──Kdf.derive(备份密码, params)──► KEK
 *        KEK + aad(=头) + 明文 ──Aead.seal──► blob(含 iv/ct/tag)
 *        头 + iv + blob ──BackupFormat.pack──► VBCK 文件
 * ```
 * 注意 KEK 是**每次导出新派的**（新 salt），所以同样的密码导出两次，
 * 文件内容完全不同，也不存在"比对两个文件的密文"这种攻击面。
 *
 * 纯 JVM，无 Android 依赖，可直接单测。
 */
object BackupCrypto {

    /**
     * 加密成一个完整的备份文件。
     *
     * @param payload 明文 JSON 字节（来自 `BackupCodec.encode`）
     * @param password 备份密码
     * @param params KDF 参数；默认用与主密码同一套（210k 次 PBKDF2）。
     *        单测里会传低迭代参数，否则每条用例要跑 0.2 秒以上。
     */
    fun seal(
        payload: ByteArray,
        password: CharArray,
        params: KdfParams = KdfParams.newDefault()
    ): ByteArray {
        val kdfEncoded = params.encode()
        // AAD 就是头部字节。必须先算出来（此时还没有 IV 与密文）
        val aad = BackupFormat.headerBytes(kdfEncoded)

        val kek = Kdf.derive(password, params)
        try {
            val blob = Aead.seal(kek, aad, payload)
            // blob 自带 iv（CryptoBlob 的 header 里），容器再存一份是为了让格式自描述：
            // 以后即便换了 blob 内部布局，光看文件也能知道 IV 是什么
            val iv = CryptoBlob.unpack(blob).iv
            return BackupFormat.pack(kdfEncoded, iv, blob)
        } finally {
            kek.fill(0)
        }
    }

    /**
     * 解开一个备份文件。
     *
     * @return 明文 JSON 字节
     * @throws BackupPasswordException 密码不对，**或**文件头/密文被改动
     * @throws BackupFormatException 不是本应用的备份、版本不支持、文件截断
     */
    fun open(fileBytes: ByteArray, password: CharArray): ByteArray {
        // 先验结构（magic/version/长度），结构不对就没必要浪费 0.2 秒去派生密钥
        val aad = BackupFormat.aad(fileBytes)
        val container = BackupFormat.unpack(fileBytes)

        val params = try {
            KdfParams.decode(container.kdfParams)
        } catch (e: Exception) {
            throw BackupFormatException("备份文件的密钥参数无法解析", e)
        }

        val kek = try {
            Kdf.derive(password, params)
        } catch (e: IllegalArgumentException) {
            throw BackupFormatException("备份文件的密钥参数不合法", e)
        }

        try {
            return Aead.open(kek, aad, container.ciphertext)
        } catch (e: TamperedDataException) {
            // GCM 认证失败把所有情况都归到这一个异常里：密码错、头被改、密文被改。
            // 无法区分（也没有意义去区分）—— 对用户来说是同一句话。
            throw BackupPasswordException(e)
        } finally {
            kek.fill(0)
        }
    }

    /**
     * 只读文件头里的 KDF 参数，不派生密钥、不解密。
     *
     * 用途：导入页在弹密码框之前，先确认"这确实是一个备份文件"，
     * 顺便展示它是哪个版本导出的，避免用户对着错误文件反复输密码。
     */
    fun peekParams(fileBytes: ByteArray): KdfParams {
        val container = BackupFormat.unpack(fileBytes)
        return KdfParams.decode(container.kdfParams)
    }
}

/**
 * 备份密码不对 —— 也可能是文件被改过。
 *
 * 与 `WrongPasswordException`（主密码错）分开，因为两条链路的密钥来源不同：
 * 主密码错的时候用户的补救办法是"再想想"，备份密码错的时候还有
 * "是不是拿错文件了"这一种可能，UI 提示要把两者都提到。
 */
class BackupPasswordException(cause: Throwable? = null) :
    RuntimeException("备份密码不正确，或文件已被修改", cause)
