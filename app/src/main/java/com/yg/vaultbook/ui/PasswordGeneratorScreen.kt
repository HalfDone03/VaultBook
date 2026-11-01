package com.yg.vaultbook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import com.yg.vaultbook.core.util.PasswordGenerator
import com.yg.vaultbook.core.util.PasswordStrength
import kotlin.math.roundToInt

/**
 * 独立密码生成器。
 *
 * 为什么单独一页而不是弹窗：生成密码经常发生在"还没想好去哪个网站注册"的时候，
 * 是个独立的工具动作。做成页面就能随时进出、随时复制，不必先创建一条记录。
 * 编辑页里的「生成」按钮仍然保留（那条路径更快）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordGeneratorScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var options by remember { mutableStateOf(PasswordGenerator.Options()) }
    // 生成结果是普通 String：它要显示在屏幕上，只能是 String。
    // 真正需要清零的是仓储层解出来的那些 CharArray（见 EntryDetailScreen）。
    var password by remember { mutableStateOf("") }

    // 选项一变就重新生成 —— 用户调长度时希望马上看到长度变化，
    // 而不是再去点一次「重新生成」。
    LaunchedEffect(options) {
        password = PasswordGenerator.generate(options)
    }

    val chars = remember(password) { password.toCharArray() }
    val score = remember(chars) { PasswordStrength.score(chars) }
    val bits = remember(options) { PasswordGenerator.entropyBits(options) }

    Scaffold(
        topBar = {
            VaultTopBar(
                title = "生成密码",
                subtitle = "字符集与长度可调",
                onBack = onBack
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // 密码本身是这页的主角，给它一条深绿面板：白字等宽，跟列表页头图
            // 同一套深绿。之前是一张白卡，密码和下面的说明挤在一起，读不出主次。
            val dotColor = when {
                score <= 1 -> Color(0xFFFF9E8F)
                score == 2 -> Color(0xFFF3C46B)
                else -> Color(0xFF7BE8B4)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Brush.verticalGradient(listOf(HeroTop, HeroBottom)))
                    .padding(horizontal = 18.dp, vertical = 20.dp)
            ) {
                Column {
                    Text(
                        text = password,
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = "约 ${bits.roundToInt()} bit · ${PasswordStrength.labelOf(score)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.78f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { password = PasswordGenerator.generate(options) },
                    modifier = Modifier.weight(1f)
                ) { Text("重新生成") }
                OutlinedButton(
                    onClick = { copySecret(context, "密码", password) },
                    modifier = Modifier.weight(1f)
                ) { Text("复制") }
            }

            Spacer(Modifier.height(24.dp))
            Text("长度 ${options.length}", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = options.length.toFloat(),
                onValueChange = { options = options.copy(length = it.roundToInt()) },
                valueRange = PasswordGenerator.MIN_LENGTH.toFloat()..PasswordGenerator.MAX_LENGTH.toFloat()
            )

            Spacer(Modifier.height(8.dp))
            Text(
                text = "包含",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GroupChip("小写", options.lower, Modifier.weight(1f)) {
                    options = toggle(options, Group.LOWER)
                }
                GroupChip("大写", options.upper, Modifier.weight(1f)) {
                    options = toggle(options, Group.UPPER)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GroupChip("数字", options.digits, Modifier.weight(1f)) {
                    options = toggle(options, Group.DIGITS)
                }
                GroupChip("符号", options.symbols, Modifier.weight(1f)) {
                    options = toggle(options, Group.SYMBOLS)
                }
            }

            Spacer(Modifier.height(20.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "字符集刻意去掉了 l I 1 O 0 —— 这密码经常要人眼读、手抄、" +
                            "或在不能粘贴的设备上手输。长度补偿到 20 位，熵仍在 120 bit 以上。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "符号只保留 !@#%^&*-_=+? —— 不含引号、反斜杠、空格、" +
                            "$ 和反引号，它们在命令行里要转义，也常被网站后端过滤。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(48.dp))
        }
    }
    // 页面离开时清掉这份 CharArray 拷贝（String 本身无法清零）
    DisposableEffect(chars) {
        onDispose { chars.fill('\u0000') }
    }
}

private enum class Group { LOWER, UPPER, DIGITS, SYMBOLS }

/**
 * 切换一类字符。
 *
 * 不允许把最后一类关掉：字符池为空时生成器没有合法输出。
 * 用户点掉最后一个时会"看起来没反应"，这比让界面崩掉好。
 */
private fun toggle(options: PasswordGenerator.Options, group: Group): PasswordGenerator.Options {
    val next = when (group) {
        Group.LOWER -> options.copy(lower = !options.lower)
        Group.UPPER -> options.copy(upper = !options.upper)
        Group.DIGITS -> options.copy(digits = !options.digits)
        Group.SYMBOLS -> options.copy(symbols = !options.symbols)
    }
    return if (next.enabledGroups == 0) options else next
}

@Composable
private fun GroupChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier
    )
}
