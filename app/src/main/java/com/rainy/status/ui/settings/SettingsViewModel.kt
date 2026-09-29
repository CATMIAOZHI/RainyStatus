package com.rainy.status.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainy.status.R
import com.rainy.status.data.local.AppSettings
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
)

/** 设置页里的保活区块状态 */
data class KeepAliveUi(
    val ignoringBatteryOptimizations: Boolean = false,
    val canScheduleExactAlarms: Boolean = false,
    val hasNotificationPermission: Boolean = false,
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
    @ApplicationContext private val appContext: Context,
    /**
     * 应用级作用域：给「失焦即落盘」用。
     *
     * 不能用 viewModelScope —— 用户改完地址直接按返回键时，ViewModel 会被清除、
     * viewModelScope 随之取消，写入就丢了。appScope 与进程同寿命，写入一定能落地。
     */
    @Named(ScopeQualifiers.APP) private val appScope: CoroutineScope,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /**
     * 三个文本框的落盘防抖任务。
     *
     * 为什么不用「失焦时提交」：`onFocusChanged` 的回调与 NavHost 的 pop 是竞态的，
     * 「改完地址直接按返回键」会让协程还没执行就被 `viewModelScope` 取消 → **静默丢输入**，
     * 而且 `endpointError` 从未被设置，用户只会发现「设置了但没生效」。
     * 也不能立刻落盘：边输边写会让 `LaunchedEffect(settings.endpoint)` 回灌、
     * 覆盖用户正在打的字。所以走「边输边防抖落盘」，与首页草稿同一套模式。
     *
     * 防抖任务本身也跑 [appScope]（不是 viewModelScope）：`onFocusChanged` 不一定触发
     * （系统返回手势、弹窗收起时焦点常保持不变），若最后一次输入后 600ms 内离开页面，
     * 跑在 viewModelScope 的任务会被取消，这次输入就丢了。appScope 与进程同寿命，
     * 保证「最后一次输入」一定落地。
     */
    private var endpointJob: Job? = null
    private var tokenJob: Job? = null
    private var deviceNameJob: Job? = null

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
        refreshKeepAlive()
    }

    fun refreshKeepAlive() {
        _uiState.update {
            it.copy(
                keepAlive = KeepAliveUi(
                    ignoringBatteryOptimizations = PermissionUtils.isIgnoringBatteryOptimizations(appContext),
                    canScheduleExactAlarms = PermissionUtils.canScheduleExactAlarms(appContext),
                    hasNotificationPermission = PermissionUtils.hasNotificationPermission(appContext),
                )
            )
        }
    }

    // ── 服务器 ──

    /**
     * 地址边输边落盘（防抖 [COMMIT_DEBOUNCE_MS]）。
     *
     * 校验错误延后到防抖之后才显示：正在输入时把中间态（`https://` 还没打完）标红
     * 会把「还在打字」误判成「填错了」。
     */
    fun onEndpointChange(value: String) {
        endpointJob?.cancel()
        // 用 appScope 而非 viewModelScope：离开页面时 viewModelScope 会被取消，
        // 最后一次输入若还在防抖窗口里就丢了（onFocusChanged 不保证触发）
        endpointJob = appScope.launch {
            delay(COMMIT_DEBOUNCE_MS)
            commitEndpoint(value)
        }
    }

    /**
     * 失焦时立刻落盘并取消待执行的防抖任务。
     *
     * 这一步是**必须**的：用户改完地址直接按返回键时，`onFocusChanged` 的回调
     * 可能早于 NavHost 的 pop，若仍等防抖就会被 `viewModelScope` 取消而丢输入。
     * 因此这里取消防抖并**改走 appScope**，保证写入在 ViewModel 清除过程中也能完成。
     */
    fun commitEndpointNow(value: String) {
        endpointJob?.cancel()
        endpointJob = null
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            // 报错而不是静默 return：防抖路径（commitEndpoint）对同一输入会提示「必填」，
            // 这里若什么都不做，用户看到的是「输入框空着、磁盘里还是旧地址、零提示」，
            // 随后点「连接测试」测的仍是旧地址——与 P1-1 同型的静默陷阱。
            _uiState.update { it.copy(endpointError = UiText.Resource(R.string.settings_endpoint_required)) }
            return
        }
        if (EndpointNormalizer.normalize(trimmed) is EndpointNormalizer.Result.Valid) {
            appScope.launch { settingsStore.setEndpoint(trimmed) }
        } else {
            _uiState.update { it.copy(endpointError = UiText.Resource(R.string.settings_endpoint_error)) }
        }
    }

    /** 提交地址：非法输入立即提示，合法才落盘 */
    private suspend fun commitEndpoint(value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(endpointError = UiText.Resource(R.string.settings_endpoint_required)) }
            return
        }
        when (EndpointNormalizer.normalize(trimmed)) {
            is EndpointNormalizer.Result.Valid -> {
                _uiState.update { it.copy(endpointError = null) }
                settingsStore.setEndpoint(trimmed)
            }
            else -> _uiState.update { it.copy(endpointError = UiText.Resource(R.string.settings_endpoint_error)) }
        }
    }

    fun onTokenChange(value: String) {
        tokenJob?.cancel()
        tokenJob = appScope.launch {
            delay(COMMIT_DEBOUNCE_MS)
            commitToken(value)
        }
    }

    /** 失焦时立刻落盘（同 [commitEndpointNow] 的理由） */
    fun commitTokenNow(value: String) {
        tokenJob?.cancel()
        tokenJob = null
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            // 同 commitEndpointNow：静默 return 会让「清空 Token → 失焦」变成
            // 输入框空着、磁盘里仍是旧 Token、零提示，随后测试用的还是旧值。
            _uiState.update { it.copy(tokenError = UiText.Resource(R.string.settings_token_required)) }
            return
        }
        appScope.launch { settingsStore.setToken(trimmed) }
    }

    private suspend fun commitToken(value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(tokenError = UiText.Resource(R.string.settings_token_required)) }
            return
        }
        _uiState.update { it.copy(tokenError = null) }
        settingsStore.setToken(trimmed)
    }

    fun onDeviceNameChange(value: String) {
        deviceNameJob?.cancel()
        deviceNameJob = appScope.launch {
            delay(COMMIT_DEBOUNCE_MS)
            settingsStore.setDeviceName(value.trim())
        }
    }

    /** 失焦时立刻落盘（设备名没有校验失败态，直接写） */
    fun commitDeviceNameNow(value: String) {
        deviceNameJob?.cancel()
        deviceNameJob = null
        appScope.launch { settingsStore.setDeviceName(value.trim()) }
    }

    fun testConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(testState = TestState.Testing) }
            val state = when (val result = repository.testConnection()) {
                is ConnectionTestResult.Ok -> TestState.Ok(result.httpCode)
                is ConnectionTestResult.Unauthorized -> TestState.Unauthorized(result.httpCode)
                is ConnectionTestResult.Failed -> TestState.Failed(describeError(result.error))
                ConnectionTestResult.NotConfigured -> TestState.NotConfigured
            }
            _uiState.update { it.copy(testState = state) }
        }
    }

    // ── 上报 ──

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
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

    fun setNotifyEnabled(value: Boolean) {
        viewModelScope.launch { settingsStore.setNotifyEnabled(value) }
    }

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

    private companion object {
        /**
         * 三个文本框的落盘防抖。
         *
         * 比首页心情草稿（500ms）略长：设置项改一次就够，用户可能连续改错再改，
         * 稍长能少写几次 DataStore；而失焦路径是立即落盘的，不会因此丢输入。
         */
        const val COMMIT_DEBOUNCE_MS = 600L
    }
}