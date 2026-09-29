package com.rainy.status

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rainy.status.data.local.AppSettings
import com.rainy.status.ui.MainViewModel
import com.rainy.status.ui.RainyStatusNavHost
import com.rainy.status.ui.components.RainyBackground
import com.rainy.status.ui.theme.RainyStatusTheme
import com.rainy.status.util.LocaleManager
import dagger.hilt.android.AndroidEntryPoint

/**
 * APP 入口。
 *
 * 布局层次：Theme（品牌色 + 排版 + 明暗）→ RainyBackground（渐变底）→ NavHost。
 * **不在外层再套 Scaffold**：页面各自用 Scaffold 处理 TopAppBar 与 insets，
 * 双层 Scaffold 会让 padding 计算重复。
 *
 * 主题明暗来自用户设置（跟随系统 / 浅色 / 深色），因此必须在这里读 ViewModel，
 * 而不是让 Theme 自己去问系统——否则用户固定浅色时界面会跟着系统变深。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        // 应用内语言偏好在此层生效：系统重建 Activity 后资源按所选语言重新解析
        super.attachBaseContext(LocaleManager.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: MainViewModel = hiltViewModel()
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dark = when (themeMode) {
                AppSettings.THEME_LIGHT -> false
                AppSettings.THEME_DARK -> true
                else -> isSystemInDarkTheme()
            }

            RainyStatusTheme(darkTheme = dark) {
                RainyBackground(dark = dark) {
                    RainyStatusNavHost()
                }
            }
        }
    }
}