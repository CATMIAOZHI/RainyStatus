package com.rainy.status.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.rainy.status.ui.components.DebugLogScreen
import com.rainy.status.ui.home.HomeScreen
import com.rainy.status.ui.moodhistory.MoodHistoryScreen
import com.rainy.status.ui.settings.SettingsScreen

/** 路由名集中在此，避免在多个文件里散落字符串 */
object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val DEBUG_LOG = "debug_log"
    const val MOOD_HISTORY = "mood_history"
}

/**
 * 返回防连点围栏。
 *
 * 用普通类持有时间戳而不是 `mutableStateOf`：写状态会触发重组，
 * 若恰好发生在过渡动画期间会干扰动画状态机，出现空白页。
 */
private class PopGuard(private val cooldownMs: Long = 200) {
    private var lastPopTime = 0L

    fun tryAcquire(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastPopTime < cooldownMs) return false
        lastPopTime = now
        return true
    }

    fun reset() {
        lastPopTime = 0L
    }
}

/**
 * 应用导航图（单栈，无需自适应双窗格——只有四个页面）。
 *
 * 过渡动画显式用同一套滑动 + 淡入：Navigation Compose 会把 pop 过渡绑到
 * Android 13+ 的预测性返回手势进度上，若只定义 enter/exit，返回时会出现
 * 「淡出与滑动混用」的观感突变。
 *
 * `PopGuard`：200ms 时间戳围栏，防止返回按钮被连点时连续 pop 两次
 * （连点会让栈一次退两层，用户会以为自己点错了）。
 */
@Composable
fun RainyStatusNavHost() {
    val navController = rememberNavController()
    val popGuard = remember { PopGuard() }
    val guardedPop: () -> Unit = {
        // 起始页没有上一个条目，直接跳过，避免无意义的 popBackStack 调用
        if (navController.previousBackStackEntry != null && popGuard.tryAcquire()) {
            if (!navController.popBackStack()) popGuard.reset()
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        enterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(220)
            ) + fadeIn(animationSpec = tween(90))
        },
        exitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(220)
            ) + fadeOut(animationSpec = tween(90))
        },
        popExitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(220)
            ) + fadeOut(animationSpec = tween(90))
        },
        popEnterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(220)
            ) + fadeIn(animationSpec = tween(90))
        }
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenMoodHistory = { navController.navigate(Routes.MOOD_HISTORY) }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = guardedPop,
                onOpenDebugLog = { navController.navigate(Routes.DEBUG_LOG) },
                onOpenMoodHistory = { navController.navigate(Routes.MOOD_HISTORY) }
            )
        }
        composable(Routes.DEBUG_LOG) {
            DebugLogScreen(onBack = guardedPop)
        }
        composable(Routes.MOOD_HISTORY) {
            MoodHistoryScreen(onBack = guardedPop)
        }
    }
}