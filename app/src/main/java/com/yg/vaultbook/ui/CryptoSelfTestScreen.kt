package com.yg.vaultbook.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.yg.vaultbook.core.crypto.CryptoSelfTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 加密层自检页。
 *
 * 用途：装到**你这台手机**上确认 PBKDF2 / AES-GCM / 密钥包裹真的可用。
 * 部分定制 ROM 的 `SecureRandom` 或 `PBKDF2WithHmacSHA256` 会有异常行为，
 * 单测跑在 JVM 上是查不出来的。
 *
 * W1 没把它挂到界面上（导航只有解锁/列表/编辑）。W4 加设置页时挂成
 * 「高级 → 运行加密自检」一项即可，`CryptoSelfTest` 与这个 Composable 都留着。
 */
@Composable
fun CryptoSelfTestScreen() {
    var result by remember { mutableStateOf<CryptoSelfTest.Result?>(null) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("加密层自检", style = MaterialTheme.typography.headlineSmall)
        Text(
            "在本机跑一遍完整链路：PBKDF2 派生 → 生成 DEK/dbKey → " +
                "AES-256-GCM 字段加解密 → 改主密码（验证 DEK 不变）→ 设备密钥包裹。",
            style = MaterialTheme.typography.bodyMedium
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    running = true
                    scope.launch {
                        // PBKDF2 210k 次迭代在主线程会明显卡顿，放到 Default 线程池
                        val r = withContext(Dispatchers.Default) { CryptoSelfTest.run() }
                        result = r
                        running = false
                    }
                },
                enabled = !running
            ) {
                Text(if (running) "自检中…" else "运行加密自检")
            }
            if (running) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
            }
        }

        result?.let { r ->
            HorizontalDivider()

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (r.ok) "全部通过 · ${r.elapsedMs} ms"
                        else "存在失败项 · ${r.elapsedMs} ms",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (r.ok) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                    r.steps.forEach { step ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = if (step.ok) "✓" else "✗",
                                color = if (step.ok) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                            )
                            Column {
                                Text(step.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = step.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
