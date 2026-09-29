package com.rainy.status.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainy.status.data.local.AppSettings
import com.rainy.status.data.local.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 顶层 ViewModel：只负责给 [MainActivity] 提供主题模式。
 *
 * 单独开一个而不是复用设置页的 ViewModel：主题要在 Activity 级别生效，
 * 而设置页 ViewModel 只在设置路由存活，拿不到「App 启动时」的值。
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    settingsStore: SettingsStore,
) : ViewModel() {

    /** 用户选择的主题模式（system / light / dark） */
    val themeMode: StateFlow<String> = settingsStore.settings
        .map { it.themeMode }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = AppSettings.THEME_SYSTEM,
        )
}