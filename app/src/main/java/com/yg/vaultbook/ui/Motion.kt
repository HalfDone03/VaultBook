package com.yg.vaultbook.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 全工程的动效时长。
 *
 * 集中在这里的理由：散落在各页面的 `tween(200)` / `tween(300)` 会让界面
 * 看起来"每块各走各的"。统一之后，同一个动作在任何页面都是同一种节奏。
 *
 * 这些数字不是随便挑的 —— 150ms 以下感觉不到"动了"，350ms 以上会觉得卡。
 * 列表项之间有 40ms 的错落，最多累积 240ms（再多用户就开始等）。
 */
internal object Motion {
    /** 状态切换（勾选、展开小三角） */
    const val Fast = 150

    /** 常规进入/退出 */
    const val Base = 240

    /** 大块内容（整页、整个空状态） */
    const val Slow = 360

    /** 列表项之间错开的步长 */
    const val StaggerStep = 40

    /** 错落累积的上限 —— 超过 240ms 用户会开始等 */
    const val StaggerMax = 240
}

/**
 * 按压缩放。
 *
 * 用手指按下去有 3% 的收缩，是这个界面里"有质感"的主要来源 ——
 * 比涟漪（ripple）更明确，因为它连图标和文字一起缩，看着像按钮被压下去。
 *
 * 用的是 spring 而不是 tween：回弹时稍微过冲一点（dampingRatio 0.55），
 * 手感更像实体按键；tween 会是匀速回位，显得"塑料"。
 */
@Composable
internal fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.97f
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "pressScale"
    )
    return this.scale(scale)
}

/**
 * 首次进入时的淡入 + 上移。
 *
 * 只在一个 composable 第一次进入组合时播一次。这里用 `remember` 的布尔值控制，
 * 所以**离开页面再回来会重播** —— 对页面级内容是想要的（换页本身就有动效），
 * 但不要用它包列表行，那样每次滚动回收都会重播。
 */
@Composable
internal fun Modifier.enterFadeSlide(
    delayMillis: Int = 0,
    distanceDp: Float = 16f,
    durationMillis: Int = Motion.Slow
): Modifier {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }

    val progress by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(
            durationMillis = durationMillis,
            delayMillis = delayMillis,
            easing = FastOutSlowInEasing
        ),
        label = "enterFadeSlide"
    )

    return this
        .graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * distanceDp * density
        }
}

/**
 * 列表项的错落延迟。
 *
 * 只对前 7 个生效（`index * 40` 累积到 240ms 封顶）。更靠后的项不延迟 ——
 * 首屏之外的内容用户看不到，给它们加延迟只会让滚动时突然冒出来。
 */
internal fun staggerDelay(index: Int): Int =
    (index.coerceAtMost(6) * Motion.StaggerStep).coerceAtMost(Motion.StaggerMax)
