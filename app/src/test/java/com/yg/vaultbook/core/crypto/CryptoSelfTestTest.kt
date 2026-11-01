package com.yg.vaultbook.core.crypto

import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoSelfTestTest {

    @Test
    fun `自检全部通过`() {
        val result = CryptoSelfTest.run(
            paramsFactory = {
                KdfParams(
                    algo = KdfAlgo.PBKDF2_SHA256,
                    iterations = 1_000,   // 单测用低迭代，避免每次跑 210k 拖慢测试
                    salt = ByteArray(KdfParams.SALT_BYTES) { 7 },
                    keyLengthBits = 256
                )
            }
        )

        val failed = result.steps.filter { !it.ok }
        assertTrue(
            "自检失败项：" + failed.joinToString("; ") { "${it.name} -> ${it.detail}" },
            failed.isEmpty()
        )
        assertTrue(result.ok)
        assertTrue("自检步骤数异常偏少：${result.steps.size}", result.steps.size >= 8)
    }
}
