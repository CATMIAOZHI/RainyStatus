package com.rainy.status.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 雨晴风格主题（与 RainyToken 同源）。
 *
 * - **不使用** dynamicColor：品牌视觉不随系统主题色漂移。
 * - 与 RainyToken 的差别：这里主题明暗由**用户设置**决定（跟随系统 / 浅色 / 深色），
 *   因此 `darkTheme` 由调用方传入，且通过 [LocalRainyDark] 下发给整个界面——
 *   [inkWarm] / [inkMuted] 这类辅助色必须跟用户选的主题一致，不能各自去问系统。
 */
private val LightColors = lightColorScheme(
    primary = StrawberryPink,
    onPrimary = PureWhite,
    primaryContainer = StrawberryPinkSoft,
    onPrimaryContainer = InkWarm,
    secondary = StrawberryPinkDark,
    onSecondary = PureWhite,
    secondaryContainer = Color(0xFFFFE4EC),
    onSecondaryContainer = InkWarm,
    tertiary = StatusGreen,
    onTertiary = PureWhite,
    background = CherryPinkLight,
    onBackground = InkWarm,
    surface = PureWhite,
    onSurface = InkWarm,
    surfaceVariant = SnowWhite,
    onSurfaceVariant = InkMuted,
    outline = InkOutline,
    outlineVariant = Color(0xFFEFE0E5),
    error = StatusRed,
    onError = PureWhite
)

private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkBackground,
    primaryContainer = StrawberryPinkDark,
    onPrimaryContainer = PureWhite,
    secondary = StrawberryPink,
    onSecondary = DarkBackground,
    secondaryContainer = Color(0xFF4A2E3A),
    onSecondaryContainer = DarkOnSurface,
    tertiary = StatusGreen,
    onTertiary = DarkBackground,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = Color(0xFF352329),
    onSurfaceVariant = Color(0xFFC9B8BE),
    outline = DarkInkOutline,
    outlineVariant = Color(0xFF3A2A30),
    error = Color(0xFFFF6B8E),
    onError = PureWhite
)

/** 当前界面是否处于深色（由用户设置解析后下发，见 [RainyStatusTheme]） */
val LocalRainyDark = staticCompositionLocalOf { false }

@Composable
fun RainyStatusTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // 不设 window.statusBarColor：targetSdk 35 起该 API 已废弃且无效
            // （系统强制 edge-to-edge），状态栏本来就透明，露出的是 RainyBackground。
            // 只需按用户所选主题切换图标明暗，否则深色背景上会出现看不清的深色图标。
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalRainyDark provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = RainyTypography,
            content = content
        )
    }
}

/** 主文本色（随用户所选主题切换） */
@Composable
fun inkWarm(): Color = if (LocalRainyDark.current) DarkInkWarm else InkWarm

/** 次要文本色（随用户所选主题切换） */
@Composable
fun inkMuted(): Color = if (LocalRainyDark.current) DarkInkMuted else InkMuted
