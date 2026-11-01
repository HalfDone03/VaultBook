package com.yg.vaultbook.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─────────────────────────────────────────────────────────────
// 列表头像色块
//
// 为什么需要它：这一屏全是同形状的卡片，只有标题和账号两行字，
// 眼睛没有落点，扫十行也分不清哪张是哪张。给每条一个色块之后，
// **颜色本身成了索引** —— "那个绿色的淘宝"比"第三行那个"好找得多。
//
// 颜色由 seed 决定而不是标题：重命名条目不该换颜色，
// 但同一个站点的多条记录应该同色。所以调用方传 hostNorm 优先。
// ─────────────────────────────────────────────────────────────

private val AvatarPalette = listOf(
    Color(0xFF0B6E4F) to Color(0xFF18A87A), // 翡翠
    Color(0xFF2F6D8F) to Color(0xFF4FA6CC), // 雾蓝
    Color(0xFF8A5A16) to Color(0xFFC8922E), // 琥珀
    Color(0xFF5B3F9E) to Color(0xFF8B6FD4), // 紫藤
    Color(0xFF963B4C) to Color(0xFFCE6478), // 玫瑰
    Color(0xFF1F6A6A) to Color(0xFF31A0A0), // 青瓷
    Color(0xFF4F5B24) to Color(0xFF87943E), // 橄榄
    Color(0xFF7A3B6B) to Color(0xFFB45F9E)  // 品红
)

/** 取一个稳定的下标。`and 0x7FFFFFFF` 是为了躲开 `Integer.MIN_VALUE` 取绝对值仍是负数的坑。 */
private fun paletteOf(seed: String): Pair<Color, Color> =
    AvatarPalette[(seed.hashCode() and 0x7FFFFFFF) % AvatarPalette.size]

/**
 * 只取配色里的亮度端，给"小面积强调"用（列表行左侧色条之类）。
 *
 * 不直接暴露整对颜色：暗端在白底上偏脏，单独拿去做细线条会显脏；
 * 亮度端配在白卡片和深色背景上都立得住。
 */
internal fun accentOf(seed: String): Color = paletteOf(seed).second

/**
 * 标题首字。中文取第一个汉字，英文取大写首字母。
 *
 * 纯符号（比如用户把标题写成"🔐"）退回中点 —— 直接渲染 emoji 会跟
 * 色块抢注意力，而且不同系统的 emoji 字形差异很大。
 */
private fun initialOf(text: String): String {
    val ch = text.trim().firstOrNull { !it.isWhitespace() } ?: return "·"
    return if (ch.isLetterOrDigit()) ch.uppercaseChar().toString() else "·"
}

@Composable
internal fun EntryAvatar(
    title: String,
    seed: String,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp
) {
    // 配色和圆角都只跟 seed / size 有关，没必要每次重组重算一遍
    // —— 列表滚动时 EntryAvatar 会被反复组合，hash 取模虽小也是白烧的。
    val palette = remember(seed) { paletteOf(seed) }
    val shape = remember(size) { RoundedCornerShape(size * 0.30f) }
    val (from, to) = palette

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(Brush.linearGradient(listOf(from, to)))
            // 一道极淡的内描边：纯渐变块落在白卡片上，边缘会发虚，
            // 描一圈半透明白就能把形状"钉"住，深色模式下也同样成立。
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initialOf(title),
            color = Color.White,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            // 中文字符在圆角方块里偏下一点，行高收窄能让它更居中
            lineHeight = (size.value * 0.48f).sp
        )
    }
}

/**
 * 空状态插画。
 *
 * 用 Canvas 画而不是引一张图：APK 已经 31 MB 了，为一张一次性插画
 * 再加一套多密度位图不值得；矢量画法在任何屏幕上都不会糊，
 * 而且能跟着主题色走（深色模式下自动变亮绿）。
 */
@Composable
internal fun EmptyArtwork(modifier: Modifier = Modifier, size: Dp = 132.dp) {
    val primary = MaterialTheme.colorScheme.primary
    val outline = primary.copy(alpha = 0.55f)
    val fill = primary.copy(alpha = 0.10f)

    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height

        // 本子本体：淡填充 + 描边
        val left = w * 0.13f
        val top = h * 0.10f
        val right = w * 0.87f
        val bottom = h * 0.90f
        val radius = CornerRadius(w * 0.09f, w * 0.09f)
        val bodySize = Size(right - left, bottom - top)

        drawRoundRect(
            color = fill,
            topLeft = Offset(left, top),
            size = bodySize,
            cornerRadius = radius
        )
        drawRoundRect(
            color = outline,
            topLeft = Offset(left, top),
            size = bodySize,
            cornerRadius = radius,
            style = Stroke(width = w * 0.017f)
        )

        // 三条记录 + 右侧站点圆点
        val lineLeft = left + w * 0.085f
        val lineWidth = (right - left) - w * 0.30f
        val lineHeight = h * 0.040f
        listOf(0.30f, 0.45f, 0.60f).forEachIndexed { index, fy ->
            drawRoundRect(
                color = outline,
                topLeft = Offset(lineLeft, h * fy),
                size = Size(lineWidth * (1f - index * 0.12f), lineHeight),
                cornerRadius = CornerRadius(lineHeight / 2f, lineHeight / 2f)
            )
            drawCircle(
                color = primary.copy(alpha = 0.70f),
                radius = w * 0.020f,
                center = Offset(right - w * 0.105f, h * fy + lineHeight / 2f)
            )
        }

        // 右下角一把锁：锁体 + 锁梁
        val cx = right - w * 0.10f
        val cy = bottom - h * 0.10f
        val badge = w * 0.135f
        drawCircle(color = primary, radius = badge, center = Offset(cx, cy))
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(cx - w * 0.042f, cy - h * 0.010f),
            size = Size(w * 0.084f, h * 0.058f),
            cornerRadius = CornerRadius(w * 0.016f, w * 0.016f)
        )
        drawArc(
            color = Color.White,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(cx - w * 0.030f, cy - h * 0.042f),
            size = Size(w * 0.060f, h * 0.060f),
            style = Stroke(width = w * 0.014f)
        )
    }
}

/**
 * 统一顶栏。
 *
 * 不用 M3 的 TopAppBar 是因为它把标题压成 22sp、返回键是裸图标，跟首页的深绿头图放一起像两套设计。
 * 这里统一成「圆形玻璃返回键 + headlineSmall 标题 + labelMedium 副标题」，
 * 底色与页面同为 background、不画分隔线，靠字号和留白分层。
 */
@Composable
internal fun VaultTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack,
    backLabel: String = "返回",
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            TopBarButton(icon = backIcon, description = backLabel, onClick = onBack)
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        actions()
    }
}

/** 顶栏上的圆形按钮，跟提示条的玻璃质感一致。 */
@Composable
internal fun RowScope.TopBarButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true,
    badge: Int = 0
) {
    val source = remember { MutableInteractionSource() }
    val backdrop = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.60f else 0.26f)
    val button: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(backdrop)
                .pressScale(source, pressedScale = 0.92f)
                .clickable(
                    interactionSource = source,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = if (enabled) tint else tint.copy(alpha = 0.38f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
    if (badge > 0) {
        BadgedBox(badge = { Badge { Text(if (badge > 99) "99+" else "$badge") } }) { button() }
    } else {
        button()
    }
}

/**
 * 小标题：设置、备份这类页面用它分堆。
 *
 * 字号小、字距拉开一点，读起来像"标签"而不是"标题"，
 * 这样它下面的卡片才是视觉主体，标题不去跟内容抢注意力。
 */
@Composable
internal fun VaultSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 2.dp)
    )
}

/** 统一的白卡片：20dp 圆角、1dp 阴影。三个页面之前各写各的圆角，这里收口。 */
@Composable
internal fun VaultCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(content = content)
    }
}

/** 卡片内的分隔线，左侧留出图标宽度，视觉上从文字起始处开始。 */
@Composable
internal fun VaultRowDivider(startPadding: Dp = 66.dp) {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        modifier = Modifier.padding(start = startPadding)
    )
}

/**
 * 卡片内的一行入口：圆角色块图标 + 标题 + 说明 + 右箭头。
 *
 * 图标底色用 accent 的 13% 透明：够淡，不抢标题；同色图标压在上面又有辨识度。
 */
@Composable
internal fun VaultActionRow(
    icon: ImageVector,
    accent: Color,
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = if (enabled) 0.13f else 0.06f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) accent else accent.copy(alpha = 0.40f),
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp)
        )
    }
}
/**
 * 头图用的深绿两端色。
 *
 * 写死而不是取主题色：深色主题下 primary 是亮薄荷 `#4ADE9B`，压白字完全糊；
 * 头图这种"大面积色块 + 白字"的地方需要固定的深色端。
 */
internal val HeroTop = Color(0xFF11825C)
internal val HeroBottom = Color(0xFF053726)