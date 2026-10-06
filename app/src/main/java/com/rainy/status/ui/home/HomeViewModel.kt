package com.rainy.status.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainy.status.R
import com.rainy.status.data.device.DeviceStateReader
import com.rainy.status.data.local.AppSettings
import com.rainy.status.data.local.RuntimeState
import com.rainy.status.data.local.RuntimeStateStore
import com.rainy.status.data.local.SettingsStore
import com.rainy.status.data.repository.ReportOutcome
import com.rainy.status.data.repository.StatusRepository
import com.rainy.status.domain.model.DeviceSnapshot
import com.rainy.status.domain.model.MoodEmoji
import com.rainy.status.domain.model.ReportTrigger
import com.rainy.status.domain.server.EndpointNormalizer
import com.rainy.status.service.StatusHeartbeatService
import com.rainy.status.ui.components.UiText
import com.rainy.status.ui.components.describeError
import com.rainy.status.util.PermissionUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 首页整体状态 */
data class HomeUiState(
    val settings: AppSettings = AppSettings(),
    val runtime: RuntimeState = RuntimeState(),
    /** 最近一次本地采样，用于卡片上的「当前电量」 */
    val snapshot: DeviceSnapshot? = null,
    val throttled: Boolean = false,
    val keepAlive: KeepAliveStatus = KeepAliveStatus(),
    /**
     * 心情发送成功的次数（含「内容与上次相同」）。
     *
     * 用它驱动输入框清空，而不是在按钮点击时无条件清空：
     * 发送失败时清空会把用户刚打好的字丢掉，而草稿本来就是为了防这个。
     */
    val moodSentCount: Int = 0,
    /** 正在手动上报：用于按钮防连点（MANUAL 在门控里无条件放行，连点会真写多次 KV） */
    val reporting: Boolean = false,
    /** 一次性提示（Snackbar） */
    val message: UiText? = null,
)

/** 保活检查结果 */
data class KeepAliveStatus(
    val ignoringBatteryOptimizations: Boolean = false,
    val canScheduleExactAlarms: Boolean = false,
    val hasNotificationPermission: Boolean = false,
)

/**
 * 首页 ViewModel。
 *
 * 设计要点：
 * 1. 设置与运行时状态从 DataStore 的 Flow 合并订阅，改设置后界面立刻反映；
 * 2. [refreshSnapshot] 需要 Context 读粘性广播，因此这里持有 applicationContext
 *    （只读系统 API，不碰 Activity，不会泄漏）；
 * 3. 保活状态只能在回到前台时刷新（用户可能刚从系统设置页改完权限回来），
 *    因此由 UI 的 ON_RESUME 调用 [refreshKeepAlive]。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: StatusRepository,
    private val settingsStore: SettingsStore,
    private val runtimeStateStore: RuntimeStateStore,
    private val deviceReader: DeviceStateReader,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** 草稿防抖任务：连续输入时只让最后一次真正落盘 */
    private var moodDraftJob: Job? = null

    init {
        viewModelScope.launch {
            repository.uiState.collect { (settings, runtime) ->
                _uiState.update { it.copy(settings = settings, runtime = runtime) }
            }
        }
        viewModelScope.launch {
            _uiState.update { it.copy(throttled = repository.isThrottled()) }
        }
        refreshSnapshot()
        refreshKeepAlive()
    }

    /**
     * 读一次本机状态：卡片上的「当前电量」不该只在成功上报后才更新。
     *
     * [DeviceStateReader.snapshot] 内部走 `registerReceiver(ACTION_BATTERY_CHANGED)`
     * 读**粘性广播**，这是同步的跨进程调用；放在主线程会让「回到首页」那次
     * 组合帧多等一次系统调用。因此这里显式切到 IO。
     */
    fun refreshSnapshot() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                runCatching { deviceReader.snapshot() }.getOrNull()
            } ?: return@launch
            _uiState.update { it.copy(snapshot = snapshot) }
        }
    }

    /** 刷新保活检查（回到前台时调用） */
    fun refreshKeepAlive() {
        _uiState.update {
            it.copy(
                keepAlive = KeepAliveStatus(
                    ignoringBatteryOptimizations = PermissionUtils.isIgnoringBatteryOptimizations(appContext),
                    canScheduleExactAlarms = PermissionUtils.canScheduleExactAlarms(appContext),
                    hasNotificationPermission = PermissionUtils.hasNotificationPermission(appContext),
                )
            )
        }
    }

    /** 手动补一次上报的本地状态（用于 Snackbar 文案） */
    private fun refreshAfterReport() {
        viewModelScope.launch {
            _uiState.update { it.copy(throttled = repository.isThrottled()) }
        }
        refreshSnapshot()
    }

    // ── 用户动作 ──

    /** 打开 / 关闭常驻上报 */
    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setEnabled(enabled)
            // 启动服务前先确认服务能合法前台化：通知权限缺失也不阻止（只是通知不可见）
            if (enabled) {
                StatusHeartbeatService.start(appContext)
            } else {
                StatusHeartbeatService.stop(appContext)
            }
        }
    }

    /**
     * 手动上报。
     *
     * 用 [reporting] 做防连点：`MANUAL` 在门控里是无条件放行的，连点 N 次就是
     * N 次真实 `POST /api/heartbeat` = N 次 KV 写。免费额度 1000/天，
     * 这个入口是用户最容易自己把它打爆的地方。
     */
    fun reportNow() {
        if (_uiState.value.reporting) return
        _uiState.update { it.copy(reporting = true) }
        viewModelScope.launch {
            try {
                val message = when (val outcome = repository.report(ReportTrigger.MANUAL)) {
                    is ReportOutcome.Success -> UiText.Resource(R.string.home_report_success)
                    is ReportOutcome.NotConfigured -> UiText.Resource(R.string.settings_test_not_configured)
                    is ReportOutcome.Skipped -> null
                    is ReportOutcome.Failed -> UiText.Resource(
                        R.string.home_report_failed,
                        listOf(describeError(outcome.error))
                    )
                }
                _uiState.update { it.copy(message = message) }
                refreshAfterReport()
            } finally {
                // 必须放在 finally：抛异常时按钮若停在 disabled，用户就再也点不动了
                _uiState.update { it.copy(reporting = false) }
            }
        }
    }

    fun sendMood(text: String, emoji: String) {
        viewModelScope.launch {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                _uiState.update { it.copy(message = UiText.Resource(R.string.home_mood_empty)) }
                return@launch
            }
            if (trimmed.codePointCount(0, trimmed.length) > MAX_MOOD_CODEPOINTS) {
                _uiState.update {
                    it.copy(message = UiText.Resource(R.string.home_mood_too_long, listOf(MAX_MOOD_CODEPOINTS)))
                }
                return@launch
            }

            // 表情在这里再洗一遍：界面上已经按同一规则截断过，这里是防线——
            // 超过 8 个单元会被云端直接 400，而用户只会看到一句「发送失败」
            val outcome = repository.sendMood(trimmed, MoodEmoji.toPayload(emoji))
            val message = when (outcome) {
                is ReportOutcome.Success -> UiText.Resource(R.string.home_mood_sent)
                is ReportOutcome.NotConfigured -> UiText.Resource(R.string.settings_test_not_configured)
                is ReportOutcome.Skipped -> when (outcome.reason) {
                    "mood-disabled" -> UiText.Resource(R.string.home_mood_disabled)
                    "empty" -> UiText.Resource(R.string.home_mood_empty)
                    // "unchanged"：内容与上次相同，服务端本来就不会变，静默即可
                    else -> null
                }
                is ReportOutcome.Failed -> UiText.Resource(
                    R.string.home_report_failed,
                    listOf(describeError(outcome.error))
                )
            }
            // 发送成功（或内容与上次相同）才清草稿；失败保留，用户不必重打一遍
            val unchanged = outcome is ReportOutcome.Skipped && outcome.reason == "unchanged"
            if (outcome is ReportOutcome.Success || unchanged) {
                // 必须先掐掉防抖任务：用户打完字立刻按发送时，它还在 delay 中，
                // 500ms 后会把这个刚发出去的文字写回草稿，界面上就变成「发完又自己冒出来」
                moodDraftJob?.cancel()
                moodDraftJob = null
                runtimeStateStore.setMoodDraft("", null)
                // 计数 +1 是给 UI 的清空信号：只有这一刻才允许把输入框清掉
                _uiState.update { it.copy(moodSentCount = it.moodSentCount + 1, message = message) }
            } else {
                _uiState.update { it.copy(message = message) }
            }
        }
    }

    /** 心情草稿实时落盘（防误触 / 切页丢失），带 500ms 防抖。文字与表情一起存，同一次磁盘写入 */
    fun saveMoodDraft(text: String, emoji: String) {
        moodDraftJob?.cancel()
        moodDraftJob = viewModelScope.launch {
            delay(MOOD_DRAFT_DEBOUNCE_MS)
            runtimeStateStore.setMoodDraft(text, MoodEmoji.sanitize(emoji))
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    /** 已配置时的状态页地址（去掉末尾斜杠，直接作为网页入口） */
    fun statusPageUrl(): String? {
        val normalized = EndpointNormalizer.normalize(_uiState.value.settings.endpoint)
        return (normalized as? EndpointNormalizer.Result.Valid)?.baseUrl
    }

    companion object {
        /** 与云端校验一致：心情 ≤ 140 码点（见 docs/api.md） */
        const val MAX_MOOD_CODEPOINTS = 140

        /** 草稿落盘防抖间隔：每敲一个字都写盘太浪费，见 docs/design.md 第 8.1 节 */
        private const val MOOD_DRAFT_DEBOUNCE_MS = 500L
    }
}