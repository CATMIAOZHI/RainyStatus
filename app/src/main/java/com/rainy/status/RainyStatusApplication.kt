package com.rainy.status

import android.app.Application
import android.content.Context
import com.rainy.status.util.LocaleManager
import dagger.hilt.android.HiltAndroidApp

/**
 * 应用入口。
 *
 * `@HiltAndroidApp` 触发 Hilt 组件树生成；`attachBaseContext` 里套上应用语言配置，
 * 保证 Application 级的 Context 也按用户选择的语言解析资源（不只 Activity）。
 */
@HiltAndroidApp
class RainyStatusApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleManager.wrapContext(base))
    }
}