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
import com.rainy.status.service.StatusHeartbeatService
import com.rainy.status.ui.components.UiText
import com.rainy.status.ui.components.describeError
import com.rainy.status.util.LocaleManager
import com.rainy.status.util.PermissionUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

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
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

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

    /** 提交地址（失焦 / 点保存时调用）：非法输入立即提示，合法才落盘 */
    fun commitEndpoint(value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(endpointError = UiText.Resource(R.string.settings_endpoint_required)) }
            return
        }
        when (EndpointNormalizer.normalize(trimmed)) {
            is EndpointNormalizer.Result.Valid -> {
                _uiState.update { it.copy(endpointError = null) }
                viewModelScope.launch { settingsStore.setEndpoint(trimmed) }
            }
            else -> _uiState.update { it.copy(endpointError = UiText.Resource(R.string.settings_endpoint_error)) }
        }
    }

    fun commitToken(value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(tokenError = UiText.Resource(R.string.settings_token_required)) }
            return
        }
        _uiState.update { it.copy(tokenError = null) }
        viewModelScope.launch { settingsStore.setToken(trimmed) }
    }

    fun clearTestState() {
        _uiState.update { it.copy(testState = TestState.Idle) }
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
        viewModelScope.launch {
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
        }
    }

    // ── 上报字段 ──

    fun setIncludeBattery(value: Boolean) = viewModelScope.launch { settingsStore.setIncludeBattery(value) }
    fun setIncludeCharging(value: Boolean) = viewModelScope.launch { settingsStore.setIncludeCharging(value) }
    fun setIncludeTemperature(value: Boolean) = viewModelScope.launch { settingsStore.setIncludeTemperature(value) }
    fun setIncludeNetwork(value: Boolean) = viewModelScope.launch { settingsStore.setIncludeNetwork(value) }
    fun setDeviceName(value: String) = viewModelScope.launch { settingsStore.setDeviceName(value) }

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
}