package com.example.taskassistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 配色取自网页原型：背景 #121212、卡片 #1e1e1e、主色 #4caf50、
 * 状态色 完成=绿 / 跳过=红 / 进行中=橙 / 未完成(提前结束)=红。
 *
 * 原型只有深色一套，这里固定使用深色方案，保持与原型观感一致。
 */
val AppGreen = Color(0xFF4CAF50)
val AppGreenDark = Color(0xFF1E2E1E)
val AppBackground = Color(0xFF121212)
val AppSurface = Color(0xFF1E1E1E)
val AppSurfaceVariant = Color(0xFF2A2A2A)
val AppOutline = Color(0xFF333333)
val AppTextPrimary = Color(0xFFE0E0E0)
val AppTextSecondary = Color(0xFF999999)
val AppRed = Color(0xFFE53935)
val AppOrange = Color(0xFFFF9800)

/** 组/状态颜色：与原型 .status / .set-row 的类保持一致语义。 */
object StatusColors {
    val completed = AppGreen
    val skipped = AppRed
    val inProgress = AppOrange
    val incomplete = AppRed
    val pending = AppTextSecondary
}

private val DarkColors = darkColorScheme(
    primary = AppGreen,
    onPrimary = Color.White,
    primaryContainer = AppGreenDark,
    onPrimaryContainer = AppGreen,
    secondary = AppTextSecondary,
    onSecondary = Color.Black,
    background = AppBackground,
    onBackground = AppTextPrimary,
    surface = AppSurface,
    onSurface = AppTextPrimary,
    surfaceVariant = AppSurfaceVariant,
    onSurfaceVariant = AppTextSecondary,
    outline = AppOutline,
    error = AppRed,
    onError = Color.White,
)

@Composable
fun TaskAssistantTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // 原型是深色专用，这里不跟随系统浅色，避免出现和原型不一致的观感
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography(),
        content = content,
    )
}
