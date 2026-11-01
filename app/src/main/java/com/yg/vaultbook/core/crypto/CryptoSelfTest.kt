package com.yg.vaultbook.core.crypto

/**
 * 加密层自检。
 *
 * 目的有两个：
 * 1. UI 上的「运行自检」按钮，让用户/开发者在真机上直观确认加密链路没被打断
 *    （尤其是 ROM 定制导致 `SecureRandom` 或 `PBKDF2WithHmacSHA256` 异常的情况）。
 * 2. 单测复用同一套断言，避免"文档说安全、代码没验证"的漂移。
 *
 * 纯 JVM 逻辑，不触碰 Android API，可以直接在单元测试里跑。
 */
object CryptoSelfTest {

    data class Step(val name: String, val ok: Boolean, val detail: String)

    data class Result(val ok: Boolean, val steps: List<Step>, val elapsedMs: Long)

    fun run(
        paramsFactory: () -> KdfParams = { KdfParams.newDefault() },
        masterPassword: CharArray = "自检主密码-2026".toCharArray()
    ): Result {
        val steps = mutableListOf<Step>()
        var allOk = true
        val startedNanos = System.nanoTime()

        fun check(name: String, block: () -> String) {
            try {
                steps += Step(name, true, block())
            } catch (t: Throwable) {
                allOk = false
                steps += Step(name, false, t.message ?: t.javaClass.simpleName)
            }
        }

        fun checkRejects(name: String, block: () -> Unit) {
            try {
                block()
                allOk = false
                steps += Step(name, false, "预期被拒绝，但实际上成功了")
            } catch (t: Throwable) {
                steps += Step(name, true, "已拒绝（${t.javaClass.simpleName}）")
            }
        }

        val km = KeyManager(paramsFactory = paramsFactory)
        val entryId = "e_selftest_0001"

        var created: CreatedVault? = null
        var unlockedKeys: VaultKeys? = null
        var cipher: FieldCipher? = null
        var dekSnapshot: ByteArray? = null

        try {
            // ── 1. UTF-8 往返 ──────────────────────────────────────
            check("UTF-8 编解码往返（中文 / emoji / 拉丁变音）") {
                val chars = "Pa55·中文·🔐·ñ".toCharArray()
                val bytes = Utf8.encode(chars)
                val back = Utf8.decode(bytes)
                val same = back.contentEquals(chars)
                val size = bytes.size
                bytes.fill(0)
                back.fill('\u0000')
                chars.fill('\u0000')
                if (!same) throw IllegalStateException("往返结果不一致")
                "编码为 $size 字节"
            }

            // ── 2. 初始化 + 主密码解锁 ─────────────────────────────
            check("初始化保险库并用主密码解锁") {
                val vault = km.createVault(masterPassword)
                created = vault

                val unlocked = km.unlockWithPassword(masterPassword, vault.meta)
                unlockedKeys = unlocked
                val dbKeySame = unlocked.dbKey.contentEquals(vault.keys.dbKey)
                cipher = FieldCipher(unlocked.dek)
                dekSnapshot = unlocked.dek.copyOf()

                if (!dbKeySame) throw IllegalStateException("解锁后 dbKey 不一致")
                "DEK ${unlocked.dek.size * 8} 位 / dbKey ${unlocked.dbKey.size * 8} 位"
            }

            // ── 3. 错误密码必须被拒 ────────────────────────────────
            checkRejects("错误主密码被拒绝") {
                val meta = created?.meta ?: throw IllegalStateException("前置步骤未完成")
                val wrong = "错的密码错的密码".toCharArray()
                try {
                    km.unlockWithPassword(wrong, meta)
                } finally {
                    wrong.fill('\u0000')
                }
            }

            // ── 4. 字段加密往返 ────────────────────────────────────
            check("字段加密往返（密码含中文与 emoji）") {
                val fc = cipher ?: throw IllegalStateException("前置步骤未完成")
                val secret = "我的密码🔑Aa1!".toCharArray()
                try {
                    val blob = fc.encrypt(entryId, "password", secret)
                        ?: throw IllegalStateException("加密返回 null")
                    val back = fc.decrypt(entryId, "password", blob)
                        ?: throw IllegalStateException("解密返回 null")
                    val same = back.contentEquals(secret)
                    back.fill('\u0000')
                    if (!same) throw IllegalStateException("往返结果不一致")
                    "明文 ${secret.size} 字符 → 密文 ${blob.size} 字节"
                } finally {
                    secret.fill('\u0000')
                }
            }

            // ── 5. 密文不可跨字段搬运（AAD 绑定） ──────────────────
            checkRejects("密文不可跨字段复用（AAD 绑定 entryId|fieldName）") {
                val fc = cipher ?: throw IllegalStateException("前置步骤未完成")
                val pwd = "abc".toCharArray()
                try {
                    val blob = fc.encrypt(entryId, "password", pwd)
                        ?: throw IllegalStateException("加密返回 null")
                    // 挪到同一条目的另一个字段，必须认证失败
                    fc.decrypt(entryId, "notes", blob)
                } finally {
                    pwd.fill('\u0000')
                }
            }

            // ── 6. IV 不复用：同一明文两次密文不同 ─────────────────
            check("同一明文两次加密结果不同（随机 IV）") {
                val fc = cipher ?: throw IllegalStateException("前置步骤未完成")
                val pwd = "same-input".toCharArray()
                try {
                    val a = fc.encrypt(entryId, "password", pwd)
                        ?: throw IllegalStateException("加密返回 null")
                    val b = fc.encrypt(entryId, "password", pwd)
                        ?: throw IllegalStateException("加密返回 null")
                    if (a.contentEquals(b)) {
                        throw IllegalStateException("两次密文完全相同，IV 可能被复用")
                    }
                    "密文长度 ${a.size} 字节，两次 IV 不同"
                } finally {
                    pwd.fill('\u0000')
                }
            }

            // ── 7. 改主密码：DEK 不变、旧密码失效 ──────────────────
            check("改主密码后 DEK 不变、旧密码失效、新密码可用") {
                val vault = created ?: throw IllegalStateException("前置步骤未完成")
                val snapshot = dekSnapshot ?: throw IllegalStateException("无 DEK 快照")
                val newPassword = "新的自检主密码-2026".toCharArray()
                try {
                    val changed = km.changeMasterPassword(masterPassword, newPassword, vault.meta)
                    try {
                        if (!changed.keys.dek.contentEquals(snapshot)) {
                            throw IllegalStateException("DEK 被改变了，将导致全库重新加密")
                        }
                        try {
                            km.unlockWithPassword(masterPassword, changed.meta)
                            throw IllegalStateException("旧主密码仍然可以解锁")
                        } catch (expected: WrongPasswordException) {
                            // 预期路径
                        }
                        val again = km.unlockWithPassword(newPassword, changed.meta)
                        try {
                            if (!again.dbKey.contentEquals(changed.keys.dbKey)) {
                                throw IllegalStateException("新密码解锁后 dbKey 不一致")
                            }
                            "DEK 保持 ${snapshot.size * 8} 位，旧密码已失效"
                        } finally {
                            again.destroy()
                        }
                    } finally {
                        changed.keys.destroy()
                    }
                } finally {
                    newPassword.fill('\u0000')
                }
            }

            // ── 8. 设备密钥包裹往返 ────────────────────────────────
            check("设备密钥包裹 / 解封 DEK") {
                val vault = created ?: throw IllegalStateException("前置步骤未完成")
                val wrapper = SecretKeyWrapper(ByteArray(32) { (it * 7 + 1).toByte() })
                val meta = km.enrollDeviceKey(vault.keys, wrapper, vault.meta)
                val unlocked = km.unlockWithDeviceKey(wrapper, meta)
                try {
                    if (!unlocked.dek.contentEquals(vault.keys.dek)) {
                        throw IllegalStateException("解封出的 DEK 与原始 DEK 不一致")
                    }
                    "包裹块 ${meta.wrappedDekDevice?.size ?: 0} 字节"
                } finally {
                    unlocked.destroy()
                }
            }

            // ── 9. 畸形密文必须被拒 ────────────────────────────────
            checkRejects("过短密文被拒绝") {
                CryptoBlob.unpack(ByteArray(10))
            }

            checkRejects("未知密文版本被拒绝") {
                val blob = ByteArray(CryptoBlob.MIN_BYTES)
                blob[0] = 0x09   // 非法版本
                CryptoBlob.unpack(blob)
            }

        } finally {
            masterPassword.fill('\u0000')
            dekSnapshot?.fill(0)
            unlockedKeys?.destroy()
            created?.keys?.destroy()
        }

        return Result(
            ok = allOk,
            steps = steps,
            elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000
        )
    }
}
