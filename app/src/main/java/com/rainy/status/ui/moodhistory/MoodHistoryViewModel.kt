package com.rainy.status.ui.moodhistory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainy.status.R
import com.rainy.status.data.local.MoodHistoryStore
import com.rainy.status.data.local.SettingsStore
import com.rainy.status.domain.history.MoodEvent
import com.rainy.status.ui.components.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 心情历史页状态 */
data class MoodHistoryUiState(
    /** 本机全部心情记录，最新在前（**不截断**，分组在 UI 层按本地时区做） */
    val events: List<MoodEvent> = emptyList(),
    /**
     * 「记录本机心情历史」开关当前是否打开。
     *
     * 关掉之后列表**照常显示**（已存的记录不会因为关开关而消失），页面上会多一行说明；
     * 这里只用来解释「为什么没有新的了」，不是用来隐藏列表。
     */
    val recordingEnabled: Boolean = true,
    /** 一次性提示（Snackbar） */
    val message: UiText? = null,
)

/**
 * 心情历史页 ViewModel。
 *
 * 与首页 [com.rainy.status.ui.home.HomeViewModel] 共用 [MoodHistoryStore]，但读的是**全量**
 * （[MoodHistoryStore.all]）而不是最近 3 条：首页卡片是「最近」，这一页是「全部」。
 *
 * 不做分页：`at` 是主键、表按时间物理有序，全量读一遍是本机场景下最不让人意外的做法
 * （设置页写着「已记录 312 条」，页面上就必须能找到 312 条）。
 */
@HiltViewModel
class MoodHistoryViewModel @Inject constructor(
    private val moodHistoryStore: MoodHistoryStore,
    settingsStore: SettingsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MoodHistoryUiState())
    val uiState: StateFlow<MoodHistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            moodHistoryStore.all.collect { events -> _uiState.update { it.copy(events = events) } }
        }
        viewModelScope.launch {
            settingsStore.settings.collect { settings ->
                _uiState.update { it.copy(recordingEnabled = settings.moodHistoryEnabled) }
            }
        }
    }

    /**
     * 清空本机心情历史（二次确认在 UI 层）。
     *
     * 与设置页那个入口同一个方法语义：**电量历史不受影响**，云端那份与状态页上那条也不变。
     */
    fun clear() {
        viewModelScope.launch {
            moodHistoryStore.clear()
            _uiState.update { it.copy(message = UiText.Resource(R.string.settings_mood_history_cleared)) }
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
