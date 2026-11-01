package com.yg.vaultbook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yg.vaultbook.core.util.Totp
import kotlinx.coroutines.delay

/**
 * 两因素验证码卡片（RFC 6238）。
 *
 * 三个设计决定：
 *
 * 1. **每秒重算一次，而不是"等到下一个周期再算"**。定时器漂移、系统休眠唤醒、
 *    用户改系统时间都会让"精确在边界上刷新"变成永久错位；每秒算一次 HMAC-SHA1
 *    的代价可以忽略，换掉一整类难查的时序 bug。
 *
 * 2. **剩余时间用颜色而不是只靠数字**。剩 5 秒内进度条转成 error 色 ——
 *    用户正在输码时最怕的是"输到一半码变了"，颜色比数字更快被感知。
 *
 * 3. **密钥默认折叠**。这张卡片会被别人瞥见屏幕时看到，展示验证码是必须的，
 *    但没必要把密钥一起亮出来。
 *
 * @param secretText 用户录入的内容：可以是 Base32 密钥，也可以是完整的
 *   `otpauth://totp/...` URI（从网页复制来的通常是后者）。
 */
@Composable
fun TotpCard(secretText: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // 解析失败就整张卡片不显示 —— 与其显示"密钥无效"，不如当它不存在，
    // 因为用户可能是随手把一个备用码填进了这个字段。
    val config = remember(secretText) { Totp.parseConfig(secretText) } ?: return

    // 密钥是敏感材料：离开组合时清零。config 不可变，所以这里清的是它持有的那份。
    DisposableEffect(config) {
        onDispose { config.secret.fill(0) }
    }

    var now by remember(config) { mutableLongStateOf(System.currentTimeMillis()) }
    var showSecret by remember(config) { mutableStateOf(false) }

    LaunchedEffect(config) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    val code = Totp.code(config, now)
    val remaining = Totp.remainingSeconds(config.periodSeconds, now)
    val expiring = remaining <= 5

    val accent = if (expiring) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "动态验证码",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${remaining}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = Totp.format(code),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 30.sp,
                    color = accent,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    copyPlainText(context, "验证码", code)
                }) {
                    Text("复制")
                }
            }

            Spacer(Modifier.height(10.dp))

            LinearProgressIndicator(
                progress = { remaining.toFloat() / config.periodSeconds.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(CircleShape),
                color = accent,
                trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
            )

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = buildString {
                        append("每 ${config.periodSeconds} 秒刷新")
                        if (config.algorithm != Totp.Algorithm.SHA1) {
                            append(" · ${config.algorithm.name}")
                        }
                        if (config.digits != Totp.DEFAULT_DIGITS) {
                            append(" · ${config.digits} 位")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showSecret = !showSecret }) {
                    Text(if (showSecret) "隐藏密钥" else "显示密钥")
                }
            }

            if (showSecret) {
                Text(
                    text = maskableSecret(secretText),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 显示密钥时把 `otpauth://` 前缀与参数去掉，只留 Base32 本体。 */
private fun maskableSecret(secretText: String): String {
    val parsed = Totp.parseOtpAuthUri(secretText)
    return parsed?.secretBase32 ?: secretText.trim()
}
