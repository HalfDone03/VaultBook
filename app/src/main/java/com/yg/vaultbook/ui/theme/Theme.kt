package com.yg.vaultbook.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// ─────────────────────────────────────────────────────────────
// 配色
//
// 上一版的思路是「安静、低对比」，结果做成了：背景纯白 + 卡片浅灰，
// 层次是**反的** —— 卡片比背景更暗，看起来像陷下去的旧表单。
//
// 这一版改成现代卡片的标准结构：
//   背景（带一点绿的暖灰）< 卡片（纯白）< 强调（翡翠绿）
// 白卡片浮在暖灰背景上，配极轻的阴影，层次一眼可见。
//
// 绿仍然留着（这是"本子"的气质），但饱和度提上来了：
// 原来的 #2E6B52 偏灰，在白底上像褪色的墨；#0B6E4F 才立得住。
// ─────────────────────────────────────────────────────────────

/** 品牌主色：翡翠绿。浅色模式下用深一档，保证白字对比度 ≥ 4.5:1。 */
private val Emerald = Color(0xFF0B6E4F)
private val EmeraldBright = Color(0xFF4ADE9B)
private val EmeraldDeep = Color(0xFF063D2C)

/** 次要色：琥珀金。只用在收藏星标、中等强度提示这类"提醒但不警告"的地方。 */
private val Amber = Color(0xFF8A5A16)
private val AmberBright = Color(0xFFF0C56B)

/** 第三色：雾蓝。用于辅助信息（时间、来源），与绿色拉开而不冲突。 */
private val Mist = Color(0xFF2F6D8F)
private val MistBright = Color(0xFF8ECBE8)

private val LightColors = lightColorScheme(
    primary = Emerald,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC6F0DD),
    onPrimaryContainer = EmeraldDeep,

    secondary = Amber,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFBEBC8),
    onSecondaryContainer = Color(0xFF3A2A08),

    tertiary = Mist,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD5EAF6),
    onTertiaryContainer = Color(0xFF0D3245),

    // 背景比卡片暗一档 —— 这样卡片才"浮"得起来。
    // 三个色阶拉开的顺序：background（暖灰）< surfaceVariant（近白）< surface（纯白）。
    // 列表卡片用 surface（配 1dp 阴影），次级卡片/输入框用 surfaceVariant，
    // 两者都比背景亮，不会再出现"卡片比背景更暗、看着像陷下去"的问题。
    background = Color(0xFFECF1ED),
    onBackground = Color(0xFF131715),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF131715),
    surfaceVariant = Color(0xFFF5F8F5),
    onSurfaceVariant = Color(0xFF48504B),
    surfaceTint = Emerald,

    outline = Color(0xFFB9C4BD),
    outlineVariant = Color(0xFFD6DED9),

    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

private val DarkColors = darkColorScheme(
    primary = EmeraldBright,
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF0C4E39),
    onPrimaryContainer = Color(0xFFC6F0DD),

    secondary = AmberBright,
    onSecondary = Color(0xFF452F00),
    secondaryContainer = Color(0xFF5C4310),
    onSecondaryContainer = Color(0xFFFBEBC8),

    tertiary = MistBright,
    onTertiary = Color(0xFF00344A),
    tertiaryContainer = Color(0xFF194C63),
    onTertiaryContainer = Color(0xFFD5EAF6),

    // 深色下不靠"卡片比背景亮一点"制造层次，而是靠极淡的描边 + 表面色差
    background = Color(0xFF0D1110),
    onBackground = Color(0xFFE4E9E5),
    surface = Color(0xFF161B19),
    onSurface = Color(0xFFE4E9E5),
    surfaceVariant = Color(0xFF242B27),
    onSurfaceVariant = Color(0xFFB6BFB9),
    surfaceTint = EmeraldBright,

    outline = Color(0xFF3F4842),
    outlineVariant = Color(0xFF2C3430),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

// ─────────────────────────────────────────────────────────────
// 字号
//
// 关键是**把层级拉开**。上一版 bodyLarge 16sp、bodyMedium 14sp，
// 列表标题和副标题只差 2sp，一眼看不出谁主谁次。
// 现在标题给 16sp SemiBold，副标题 13sp，差距靠字重 + 字号双重体现。
// ─────────────────────────────────────────────────────────────

private val AppTypography = Typography(
    // 大标题（列表页顶部的"VaultBook"）
    // 34sp + 更紧的字距：中文大标题在 30sp 上显得"温吞"，
    // 加大两档、字距收到 -0.8 之后才有标题该有的压场感。
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.8).sp
    ),
    // 详情页条目标题
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.5).sp
    ),
    // 各页顶栏标题
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 27.sp,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    // 列表行标题、正文
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    // 副标题、说明文字
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp
    ),
    // 顶栏右侧计数、卡片角标
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.4.sp
    )
)

// 圆角：整体比 Material 默认更大。小屏上大圆角看着更"软"，
// 也让白卡片和暖灰背景的边界不那么生硬。
private val AppShapes = Shapes(
    // extraSmall 是 M3 给输入框、Chip、小按钮用的档位。
    // 默认 4dp 会让表单看着像十年前的安卓，这里放到 12dp。
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(30.dp)
)

@Composable
fun VaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window

            // API 35 起系统强制 edge-to-edge，`statusBarColor` / `navigationBarColor`
            // 已被废弃且**不再生效**（设了也是透明的）。老版本上这两个属性仍然有用，
            // 所以按版本分流：低于 35 就主动染成背景色，35+ 交给系统 + Compose 的 insets。
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                window.statusBarColor = colors.background.toArgb()
                window.navigationBarColor = colors.background.toArgb()
            }

            // 图标明暗在任何版本都有效，也必须设 —— 否则浅色背景下状态栏图标看不清
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
