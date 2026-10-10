package com.rainy.status.ui.settings

import android.content.Context
import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainy.status.R
import com.rainy.status.data.local.AppSettings
import com.rainy.status.data.local.HistoryStore
import com.rainy.status.data.local.MoodHistoryStore
import com.rainy.status.data.local.SettingsStore
import com.rainy.status.data.repository.ConnectionTestResult
import com.rainy.status.data.repository.ReportOutcome
import com.rainy.status.data.repository.StatusRepository
import com.rainy.status.domain.model.ReportTrigger
import com.rainy.status.domain.report.WriteBudget
import com.rainy.status.domain.server.EndpointNormalizer
import com.rainy.status.di.ScopeQualifiers
import com.rainy.status.service.StatusHeartbeatService
import com.rainy.status.ui.components.UiText
import com.rainy.status.ui.components.describeError
import com.rainy.status.util.LocaleManager
import com.rainy.status.util.PermissionUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

/** 连接测试的展示状态 */
sealed interface TestState {
    data object Idle : TestState
    data object Testing : TestState
    data class Ok(val httpCode: Int) : TestState
    data class Unauthorized(val httpCode: Int) : TestState
    data class Failed(val reason: UiText) : TestState
    data object NotConfigured : TestState
}

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val writesToday: Int = 0,
    val throttled: Boolean = false,
    val testState: TestState = TestState.Idle,
    val endpointError: UiText? = null,
    val tokenError: UiText? = null,
    val message: UiText? = null,
    /** 正在手动上报：按钮防连点（MANUAL 绕过一切节流） */
    val reporting: Boolean = false,
    /** 语言偏好：null = 跟随系统 */
    val localeCode: String? = null,
    val keepAlive: KeepAliveUi = KeepAliveUi(),
    /** 本机电量历史已记录的条数（永久保留，只有手动清空才会归零） */
    val historyCount: Int = 0,
    /** 本机心情历史已记录的条数（和电量各自独立，互不影响） */
    val moodHistoryCount: Int = 0,
)

/** 设置页里的保活区块状态 */
data class KeepAliveUi(
    val ignoringBatteryOptimizations: Boolean = false,
    val canScheduleExactAlarms: Boolean = false,
    val hasNotificationPermission: Boolean = false,
    /** 本机是否为需要自启动引导的厂商；原生 Android 不显示这一行，见 PermissionUtils.needsOemAutostartGuide */
    val needsAutostartGuide: Boolean = false,
)

/**
 * 设置页 ViewModel。
 *
 * 几个约定：
 * 1. **地址与 Token 不即时校验**，只在用户点「连接测试」或失焦时给提示——
 *    边输边报错会把「正在输入」误判成「填错了」。
 * 2. 地址按 [EndpointNormalizer] 校验，**只接受 https**（与 network_security_config 一致）。
 * 3. 改动「启用」开关时立刻生效：开启拉起前台服务，关闭走通知栏同一条停止路径。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: StatusRepository,
    private val settingsStore: SettingsStore,
    private val historyStore: HistoryStore,
    private val moodHistoryStore: MoodHistoryStore,
    @ApplicationContext private val appContext: Context,
    /**
     * 应用级作用域：给「失焦即落盘」用。
     *
     * 不能用 viewModelScope —— 用户改完地址直接按返回键时，ViewModel 会被清除、
     * viewModelScope 随之取消，写入可能丢失。应用级任务不随页面销毁取消；
     * 进程终止或存储错误仍可能导致保存失败。
     */
    @Named(ScopeQualifiers.APP) private val appScope: CoroutineScope,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private enum class Input { ENDPOINT, TOKEN, DEVICE_NAME }

    /**
     * UI callbacks, debounce validation and queue bookkeeping are all Main-confined.
     * The application Job survives navigation; DataStore performs its own IO off Main.
     * Every persistence task waits for its predecessor BEFORE writing, never after.
     */
    private val inputWriter = OrderedInputWriter<Input, UiText>(
        scope = CoroutineScope(appScope.coroutineContext + Dispatchers.Main.immediate),
        debounceMillis = COMMIT_DEBOUNCE_MS,
        queue = inputQueue,
        validate = { field, value ->
            when {
                field == Input.ENDPOINT && value.isEmpty() ->
                    UiText.Resource(R.string.settings_endpoint_required)
                field == Input.ENDPOINT && EndpointNormalizer.normalize(value) !is EndpointNormalizer.Result.Valid ->
                    UiText.Resource(R.string.settings_endpoint_error)
                field == Input.TOKEN && value.isEmpty() ->
                    UiText.Resource(R.string.settings_token_required)
                else -> null
            }
        },
        onValidation = { field, error ->
            _uiState.update {
                when (field) {
                    Input.ENDPOINT -> it.copy(endpointError = error)
                    Input.TOKEN -> it.copy(tokenError = error)
                    Input.DEVICE_NAME -> it
                }
            }
        },
        persist = { field, value ->
            when (field) {
                Input.ENDPOINT -> settingsStore.setEndpoint(value)
                Input.TOKEN -> settingsStore.setToken(value)
                Input.DEVICE_NAME -> settingsStore.setDeviceName(value)
            }
        },
    )

    init {
        viewModelScope.launch {
            repository.uiState.collect { (settings, runtime) ->
                val writes = WriteBudget.normalizeWrites(
                    runtime.writesToday,
                    runtime.writesDayStartUtc,
                    System.currentTimeMillis()
                )
                _uiState.update {
                    it.copy(
                        settings = settings,
                        writesToday = writes,
                        throttled = WriteBudget.isThrottled(writes),
                    )
                }
            }
        }
        _uiState.update { it.copy(localeCode = LocaleManager.getLocaleCode(appContext)) }
        // 条数走 Flow：清空之后立刻变 0，不需要手动刷新
        viewModelScope.launch {
            historyStore.sampleCount.collect { count -> _uiState.update { it.copy(historyCount = count) } }
        }
        viewModelScope.launch {
            moodHistoryStore.count.collect { count -> _uiState.update { it.copy(moodHistoryCount = count) } }
        }
        refreshKeepAlive()
    }

    fun refreshKeepAlive() {
        _uiState.update {
            it.copy(
                keepAlive = KeepAliveUi(
                    ignoringBatteryOptimizations = PermissionUtils.isIgnoringBatteryOptimizations(appContext),
                    canScheduleExactAlarms = PermissionUtils.canScheduleExactAlarms(appContext),
                    hasNotificationPermission = PermissionUtils.hasNotificationPermission(appContext),
                    needsAutostartGuide = PermissionUtils.needsOemAutostartGuide(),
                )
            )
        }
    }

    // Restore the same raw draft after composition/activity recreation (including invalid input).
    @MainThread
    fun endpointDraft(): String? = inputWriter.draftValue(Input.ENDPOINT)
    @MainThread
    fun tokenDraft(): String? = inputWriter.draftValue(Input.TOKEN)
    @MainThread
    fun deviceNameDraft(): String? = inputWriter.draftValue(Input.DEVICE_NAME)

    // ── 服务器 ──

    /** Debounce and focus commits share one ordered, application-owned persistence queue. */
    @MainThread
    fun onEndpointChange(value: String) = inputWriter.edit(Input.ENDPOINT, value)

    @MainThread
    fun commitEndpointNow(value: String) = inputWriter.commitNow(Input.ENDPOINT, value)

    @MainThread
    fun onTokenChange(value: String) = inputWriter.edit(Input.TOKEN, value)

    @MainThread
    fun commitTokenNow(value: String) = inputWriter.commitNow(Input.TOKEN, value)

    @MainThread
    fun onDeviceNameChange(value: String) = inputWriter.edit(Input.DEVICE_NAME, value)

    @MainThread
    fun commitDeviceNameNow(value: String) = inputWriter.commitNow(Input.DEVICE_NAME, value)

    /** Do not use persisted old credentials when the latest edited revision is invalid. */
    @MainThread
    private suspend fun flushPendingInputs(): Boolean = inputWriter.flush()

    private fun inputSaveError(): UiText = UiText.Resource(
        if (inputQueue.hasFailures()) R.string.settings_save_failed
        else R.string.settings_test_not_configured
    )

    @MainThread
    fun testConnection() {
        if (_uiState.value.testState == TestState.Testing) return
        _uiState.update { it.copy(testState = TestState.Testing) }
        viewModelScope.launch {
            try {
                // Busy includes storage waits: repeated taps must not queue real heartbeats.
                if (!flushPendingInputs()) {
                    _uiState.update {
                        it.copy(testState = if (inputQueue.hasFailures()) {
                            TestState.Failed(UiText.Resource(R.string.settings_save_failed))
                        } else TestState.NotConfigured)
                    }
                    return@launch
                }
                val state = when (val result = repository.testConnection()) {
                    is ConnectionTestResult.Ok -> TestState.Ok(result.httpCode)
                    is ConnectionTestResult.Unauthorized -> TestState.Unauthorized(result.httpCode)
                    is ConnectionTestResult.Failed -> TestState.Failed(describeError(result.error))
                    ConnectionTestResult.NotConfigured -> TestState.NotConfigured
                }
                _uiState.update { it.copy(testState = state) }
            } finally {
                _uiState.update {
                    if (it.testState == TestState.Testing) it.copy(testState = TestState.Idle) else it
                }
            }
        }
    }

    // ── 上报 ──

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            // 同 testConnection：下面要读 settingsStore 判 configured，
            // 输入框里的新地址/Token 必须先落盘，否则「刚填好就打开开关」会被判成未配置。
            // 开启时若输入无效就停下：免得用旧配置把服务拉起来
            if (enabled && !flushPendingInputs()) {
                _uiState.update { it.copy(message = inputSaveError()) }
                return@launch
            }
            if (enabled) {
                // 先校验配置：地址/Token 都没填就开启只会得到一串失败日志
                val settings = settingsStore.settings.first()
                if (!settings.configured) {
                    _uiState.update { it.copy(message = UiText.Resource(R.string.settings_test_not_configured)) }
                    return@launch
                }
            }
            settingsStore.setEnabled(enabled)
            if (enabled) StatusHeartbeatService.start(appContext) else StatusHeartbeatService.stop(appContext)
        }
    }

    fun setIntervalSeconds(seconds: Int) {
        viewModelScope.launch { settingsStore.setIntervalSeconds(seconds) }
    }

    fun setAutostart(value: Boolean) {
        viewModelScope.launch { settingsStore.setAutostart(value) }
    }

    /**
     * 用户自述已在系统里打开「自启动」。
     *
     * 与首页同理：仅记录可撤销的用户确认，不读取或修改系统开关。
     * 见 [AppSettings.autostartConfirmed]。
     */
    fun confirmAutostart(confirmed: Boolean) {
        viewModelScope.launch { settingsStore.setAutostartConfirmed(confirmed) }
    }

    fun setNotifyEnabled(value: Boolean) {
        viewModelScope.launch { settingsStore.setNotifyEnabled(value) }
    }

    fun reportNow() {
        if (_uiState.value.reporting) return
        _uiState.update { it.copy(reporting = true) }
        viewModelScope.launch {
            try {
                // 同 testConnection/setEnabled：report() 也读磁盘取地址与 Token
                if (!flushPendingInputs()) {
                    _uiState.update { it.copy(message = inputSaveError()) }
                    return@launch
                }
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
            } finally {
                // 必须放在 finally：抛异常时按钮若停在 disabled，用户就再也点不动了
                _uiState.update { it.copy(reporting = false) }
            }
        }
    }

    // ── 上报字段 ──

    fun setIncludeBattery(value: Boolean) = viewModelScope.launch { settingsStore.setIncludeBattery(value) }
    fun setIncludeCharging(value: Boolean) = viewModelScope.launch { settingsStore.setIncludeCharging(value) }
    fun setIncludeTemperature(value: Boolean) = viewModelScope.launch { settingsStore.setIncludeTemperature(value) }
    fun setIncludeNetwork(value: Boolean) = viewModelScope.launch { settingsStore.setIncludeNetwork(value) }

    // ── 心情 ──

    fun setMoodEnabled(value: Boolean) = viewModelScope.launch { settingsStore.setMoodEnabled(value) }

    // ── 本机记录 ──

    /**
     * 本机电量历史开关。
     *
     * 关掉只是不再记录，**已记录的样本保留**：用户可能只是想省点磁盘写入，
     * 顺手清空历史会让人以为「关掉＝数据没了」。要清空是另一件事，见 `docs/roadmap.md`。
     */
    fun setHistoryEnabled(value: Boolean) = viewModelScope.launch { settingsStore.setHistoryEnabled(value) }

    /**
     * 清空本机历史（不可恢复）。
     *
     * 本机历史是**永久保留**的（没有自动清理），所以「清空」是这个 App 里唯一会删数据的入口：
     * 调用点必须带二次确认弹窗，别顺手接到别的按钮上。云端的历史不受影响。
     */
    fun clearHistory() {
        viewModelScope.launch {
            historyStore.clear()
            _uiState.update { it.copy(message = UiText.Resource(R.string.settings_history_cleared)) }
        }
    }

    /**
     * 本机心情历史开关。与电量那个**分开**：心情更私密，不该跟着「想看电量曲线」一起被记。
     * 关掉只是不再记新的，已有记录保留。
     */
    fun setMoodHistoryEnabled(value: Boolean) =
        viewModelScope.launch { settingsStore.setMoodHistoryEnabled(value) }

    /**
     * 清空本机心情历史（不可恢复）。
     *
     * **刻意与电量历史的清空分开**：合成一个「清空全部本机记录」的话，用户想清掉心情
     * 就会把再也回不来的电量曲线一起删掉。调用点同样必须带二次确认弹窗。
     * 云端那份不受影响。
     */
    fun clearMoodHistory() {
        viewModelScope.launch {
            moodHistoryStore.clear()
            _uiState.update { it.copy(message = UiText.Resource(R.string.settings_mood_history_cleared)) }
        }
    }

    // ── 外观 / 语言 ──

    fun setThemeMode(mode: String) = viewModelScope.launch { settingsStore.setThemeMode(mode) }

    /**
     * 切换界面语言。
     *
     * Android 13+ 由 framework 负责重建 Activity；低版本没有该机制，
     * 因此这里只保存偏好，由 UI 层在低版本上手动 `recreate()`。
     */
    fun setLocale(code: String?) {
        LocaleManager.saveLocale(appContext, code)
        _uiState.update { it.copy(localeCode = code) }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    override fun onCleared() {
        // Submit synchronously before a replacement screen can read stale persisted settings.
        inputWriter.commitAll()
        super.onCleared()
    }

    private companion object {
        // One application-wide sequence also orders writes from a recently destroyed screen.
        val inputQueue = OrderedWriteQueue()
        /**
         * 三个文本框的落盘防抖。
         *
         * 比首页心情草稿（500ms）略长：设置项改一次就够，用户可能连续改错再改，
         * 稍长能少写几次 DataStore；而失焦路径是立即落盘的，不会因此丢输入。
         */
        const val COMMIT_DEBOUNCE_MS = 600L
    }
}