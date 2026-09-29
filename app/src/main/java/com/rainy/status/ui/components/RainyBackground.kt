package com.rainy.status.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import com.rainy.status.ui.theme.CherryPinkDeep
import com.rainy.status.ui.theme.CherryPinkLight
import com.rainy.status.ui.theme.DarkBackground
import com.rainy.status.ui.theme.DarkSurface

/**
 * 雨晴风格全局背景。
 *
 * Light：樱粉渐变（#FFF0F5 → #FFD1DC）
 * Dark：暖深渐变（#1F1419 → #2A1F25）
 *
 * [dark] 必须由调用方按**用户设置的主题**传入（不要自己问系统），
 * 否则用户把主题固定为浅色时背景会跟着系统变成深色。
 */
@Composable
fun RainyBackground(
    dark: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val brush = if (dark) {
        Brush.verticalGradient(colors = listOf(DarkBackground, DarkSurface))
    } else {
        Brush.linearGradient(colors = listOf(CherryPinkLight, CherryPinkDeep))
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(brush)
    ) {
        content()
    }
}
