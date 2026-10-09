package com.rainy.status.ui.settings

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rainy.status.BuildConfig
import com.rainy.status.R
import com.rainy.status.data.local.AppSettings
import com.rainy.status.data.local.SettingsStore
import com.rainy.status.domain.report.WriteBudget
import com.rainy.status.ui.components.asString
import com.rainy.status.ui.components.resolve
import com.rainy.status.ui.theme.StatusGreen
import com.rainy.status.ui.theme.StatusOrange
import com.rainy.status.ui.theme.StatusRed
import com.rainy.status.ui.theme.StrawberryPink
import com.rainy.status.ui.theme.inkMuted
import com.rainy.status.util.PermissionUtils

private const val PROJECT_URL = "https://github.com/CATMIAOZHI/RainyStatus"

/** 语言选择里代表「跟随系统」的哨兵值（null 无法放进弹窗选项列表） */
private const val LOCALE_SYSTEM = "__system__"

/** 一个下拉/单选弹窗的选项 */
private data class Choice(val key: String, val label: String, val selected: Boolean)

/**
 * 设置页。
 *
 * 结构与设计文档第 8.1 节一致：服务器 / 上报 / 上报字段 / 心情 / 保活 / 外观 / 关于。
 *
 * 文本输入刻意**不在打字过程中校验**（见 [SettingsViewModel]）：
 * 编辑态先放在本地 state，失焦时才提交 + 提示，否则「正在输入」会被误报成「填错了」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDebugLog: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }
    val settings = state.settings

    var endpointInput by remember { mutableStateOf(viewModel.endpointDraft() ?: settings.endpoint) }
    var tokenInput by remember { mutableStateOf(viewModel.tokenDraft() ?: settings.token) }
    var tokenVisible by remember { mutableStateOf(false) }
    var deviceNameInput by remember { mutableStateOf(viewModel.deviceNameDraft() ?: settings.deviceName) }
    var showIntervalDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }

    /**
     * 三个文本框「是否曾经获得过焦点」。
     *
     * 必要性：Compose 的 `Modifier.onFocusChanged` 在**首次组合**时就会以
     * `isFocused = false` 回调一次。此时 `settings` 还是默认的 [AppSettings]（空串），
     * 于是三个字段会在用户还没碰过它们的时候就被提交一次「空值」——
     * 结果是老用户一进设置页就看到「地址是必填项」的红字，
     * 而设备名更糟：`setDeviceName("")` 会直接把已保存的设备名**清空写盘**。
     * 因此只有真正聚焦过的输入框才允许在失焦时提交。
     */
    var endpointTouched by remember { mutableStateOf(false) }
    var tokenTouched by remember { mutableStateOf(false) }
    var deviceNameTouched by remember { mutableStateOf(false) }

    /**
     * 「用户是否**打过字**」——只用于回灌守卫，与上面的 [endpointTouched] 不同。
     *
     * 必须分开：`touched` 是「聚焦过」给失焦提交用的；若拿它当回灌守卫，
     * 用户在磁盘读完前抢先聚焦（此时 `settings` 还是默认空值），
     * 初始回灌就会被跳过，输入框停在空白、磁盘里其实有值。
     */
    var endpointEdited by remember { mutableStateOf(viewModel.endpointDraft() != null) }
    var tokenEdited by remember { mutableStateOf(viewModel.tokenDraft() != null) }
    var deviceNameEdited by remember { mutableStateOf(viewModel.deviceNameDraft() != null) }

    // 首帧 settings 还是默认值，磁盘读完后要同步进输入框。
    // **只在用户还没打过字时才同步**：落盘完成后 settings 会变，
    // 若无条件回灌，用户刚敲的下一个字符会被上一次落盘的值顶掉
    // （表现为「打着打着字符自己退回去」）。
    LaunchedEffect(settings.endpoint) {
        if (!endpointEdited) endpointInput = settings.endpoint
    }
    LaunchedEffect(settings.token) {
        if (!tokenEdited) tokenInput = settings.token
    }
    LaunchedEffect(settings.deviceName) {
        if (!deviceNameEdited) deviceNameInput = settings.deviceName
    }

    // 从系统权限页回到前台后刷新保活状态
    LifecycleResumeEffect(Unit) {
        viewModel.refreshKeepAlive()
        onPauseOrDispose { }
    }

    val message = state.message
    LaunchedEffect(message) {
        if (message != null) {
            // LaunchedEffect 的 lambda 不是 @Composable 上下文，必须走 resolve(context)
            snackbarHost.showSnackbar(message.resolve(context))
            viewModel.consumeMessage()
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // 无论用户同意与否都刷新，避免界面停在旧状态
        viewModel.refreshKeepAlive()
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back),
                            tint = StrawberryPink
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 服务器 ──
            item {
                SettingsCard(stringResource(R.string.settings_group_server)) {
                    OutlinedTextField(
                        value = endpointInput,
                        onValueChange = {
                            endpointInput = it
                            endpointEdited = true
                            viewModel.onEndpointChange(it)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focus ->
                                if (focus.isFocused) {
                                    endpointTouched = true
                                } else if (endpointTouched) {
                                    // 失焦立刻落盘：用户可能紧接着按返回键，等防抖会丢输入
                                    viewModel.commitEndpointNow(endpointInput)
                                }
                            },
                        label = { Text(stringResource(R.string.settings_endpoint)) },
                        placeholder = { Text(stringResource(R.string.settings_endpoint_hint)) },
                        singleLine = true,
                        isError = state.endpointError != null,
                        supportingText = state.endpointError?.let { error -> { Text(error.asString()) } },
                        shape = RoundedCornerShape(14.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = {
                            tokenInput = it
                            tokenEdited = true
                            viewModel.onTokenChange(it)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focus ->
                                if (focus.isFocused) {
                                    tokenTouched = true
                                } else if (tokenTouched) {
                                    viewModel.commitTokenNow(tokenInput)
                                }
                            },
                        label = { Text(stringResource(R.string.settings_token)) },
                        placeholder = { Text(stringResource(R.string.settings_token_hint)) },
                        singleLine = true,
                        isError = state.tokenError != null,
                        supportingText = state.tokenError?.let { error -> { Text(error.asString()) } },
                        visualTransformation = if (tokenVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { tokenVisible = !tokenVisible }) {
                                Icon(
                                    painter = painterResource(
                                        if (tokenVisible) R.drawable.ic_visibility_off
                                        else R.drawable.ic_visibility
                                    ),
                                    contentDescription = stringResource(
                                        if (tokenVisible) R.string.settings_token_hide
                                        else R.string.settings_token_show
                                    ),
                                    tint = inkMuted()
                                )
                            }
                        },
                        shape = RoundedCornerShape(14.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    TestConnectionBlock(state = state, onTest = { viewModel.testConnection() })
                }
            }

            // ── 上报 ──
            item {
                SettingsCard(stringResource(R.string.settings_group_report)) {
                    SwitchRow(
                        title = stringResource(R.string.settings_enabled),
                        subtitle = stringResource(R.string.settings_enabled_hint),
                        checked = settings.enabled,
                        onCheckedChange = { viewModel.setEnabled(it) }
                    )
                    ClickRow(
                        title = stringResource(R.string.settings_interval),
                        value = intervalLabel(settings.intervalSeconds),
                        onClick = { showIntervalDialog = true }
                    )
                    if (settings.intervalSeconds < 300) {
                        HintText(stringResource(R.string.settings_interval_hint))
                    }
                    SwitchRow(
                        title = stringResource(R.string.settings_autostart),
                        subtitle = null,
                        checked = settings.autostart,
                        onCheckedChange = { viewModel.setAutostart(it) }
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_notify),
                        subtitle = stringResource(R.string.settings_notify_hint),
                        checked = settings.notifyEnabled,
                        onCheckedChange = { viewModel.setNotifyEnabled(it) }
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_daily_budget),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(
                                    R.string.settings_daily_budget_value,
                                    state.writesToday,
                                    WriteBudget.SOFT_LIMIT
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.throttled) StatusOrange else inkMuted()
                            )
                        }
                        Button(
                            onClick = { viewModel.reportNow() },
                            // 与同屏「连接测试」的 enabled = !testing 保持一致：
                            // ViewModel 里已有同步防连点守卫，但按钮不置灰就没有任何「上报中」反馈
                            enabled = !state.reporting,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StrawberryPink,
                                contentColor = Color.White
                            )
                        ) {
                            Text(stringResource(R.string.action_report_now))
                        }
                    }
                    HintText(stringResource(R.string.settings_daily_budget_hint))
                }
            }

            // ── 上报字段 ──
            item {
                SettingsCard(stringResource(R.string.settings_group_fields)) {
                    SwitchRow(
                        title = stringResource(R.string.settings_field_battery),
                        subtitle = null,
                        checked = settings.includeBattery,
                        onCheckedChange = { viewModel.setIncludeBattery(it) }
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_field_charging),
                        subtitle = null,
                        checked = settings.includeCharging,
                        onCheckedChange = { viewModel.setIncludeCharging(it) }
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_field_temperature),
                        subtitle = null,
                        checked = settings.includeTemperature,
                        onCheckedChange = { viewModel.setIncludeTemperature(it) }
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_field_network),
                        subtitle = null,
                        checked = settings.includeNetwork,
                        onCheckedChange = { viewModel.setIncludeNetwork(it) }
                    )
                    OutlinedTextField(
                        value = deviceNameInput,
                        onValueChange = { input ->
                            // 与云端校验上限一致（见 SettingsStore.MAX_DEVICE_NAME_LENGTH）：超长直接不接受输入
                            if (input.length <= SettingsStore.MAX_DEVICE_NAME_LENGTH) {
                                deviceNameInput = input
                                deviceNameEdited = true
                                viewModel.onDeviceNameChange(input)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .onFocusChanged { focus ->
                                if (focus.isFocused) {
                                    deviceNameTouched = true
                                } else if (deviceNameTouched) {
                                    viewModel.commitDeviceNameNow(deviceNameInput)
                                }
                            },
                        label = { Text(stringResource(R.string.settings_field_device_name)) },
                        // 超长时是"静默不接受输入"，用户会以为键盘失灵；直接显示已用字数最直观
                        supportingText = {
                            val used = deviceNameInput.length
                            val max = SettingsStore.MAX_DEVICE_NAME_LENGTH
                            Text(
                                if (used >= max) {
                                    stringResource(R.string.settings_device_name_counter_full, used, max)
                                } else {
                                    stringResource(R.string.settings_device_name_hint)
                                }
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )
                    HintText(stringResource(R.string.settings_privacy_hint))
                }
            }

            // ── 心情 ──
            item {
                SettingsCard(stringResource(R.string.settings_group_mood)) {
                    SwitchRow(
                        title = stringResource(R.string.settings_mood_enabled),
                        subtitle = stringResource(R.string.settings_mood_hint),
                        checked = settings.moodEnabled,
                        onCheckedChange = { viewModel.setMoodEnabled(it) }
                    )
                }
            }

            // ── 本机记录 ──
            item {
                SettingsCard(stringResource(R.string.settings_group_local)) {
                    SwitchRow(
                        title = stringResource(R.string.settings_history),
                        subtitle = stringResource(R.string.settings_history_hint),
                        checked = settings.historyEnabled,
                        onCheckedChange = { viewModel.setHistoryEnabled(it) }
                    )
                    // 本机历史是永久保留的，没有自动清理；「清空」是这个 App 里唯一会删数据的
                    // 入口，所以把条数摆出来、并且只有真的有数据时才给按钮（避免空按）。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.settings_history_count, state.historyCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = inkMuted(),
                            modifier = Modifier.weight(1f)
                        )
                        if (state.historyCount > 0) {
                            TextButton(onClick = { showClearHistoryDialog = true }) {
                                Text(
                                    text = stringResource(R.string.settings_history_clear),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = StatusRed
                                )
                            }
                        }
                    }
                }
            }

            // ── 保活 ──
            item {
                SettingsCard(stringResource(R.string.settings_group_keepalive)) {
                    KeepAliveActionRow(
                        title = stringResource(R.string.settings_keepalive_battery),
                        ok = state.keepAlive.ignoringBatteryOptimizations,
                        onAction = { PermissionUtils.openBatteryOptimizationSettings(context) }
                    )
                    KeepAliveActionRow(
                        title = stringResource(R.string.settings_keepalive_alarm),
                        ok = state.keepAlive.canScheduleExactAlarms,
                        onAction = { PermissionUtils.openExactAlarmSettings(context) }
                    )
                    KeepAliveActionRow(
                        title = stringResource(R.string.settings_keepalive_notification),
                        ok = state.keepAlive.hasNotificationPermission,
                        onAction = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                PermissionUtils.openAppDetails(context)
                            }
                        }
                    )
                    if (state.keepAlive.needsAutostartGuide) {
                        ClickRow(
                            title = stringResource(R.string.settings_keepalive_autostart),
                            value = if (settings.autostartConfirmed) {
                                stringResource(R.string.keepalive_state_on)
                            } else {
                                stringResource(R.string.keepalive_state_off)
                            },
                            valueColor = if (settings.autostartConfirmed) StatusGreen else StatusOrange,
                            onClick = { PermissionUtils.openAutostartSettings(context) }
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (settings.autostartConfirmed) {
                                Text(
                                    text = stringResource(R.string.keepalive_action_revoke),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = inkMuted()
                                )
                                IconButton(onClick = { viewModel.confirmAutostart(false) }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_arrow_back),
                                        contentDescription = stringResource(R.string.keepalive_action_revoke),
                                        tint = StrawberryPink
                                    )
                                }
                            } else {
                                TextButton(onClick = { viewModel.confirmAutostart(true) }) {
                                    Text(
                                        text = stringResource(R.string.keepalive_action_confirm),
                                        color = StrawberryPink
                                    )
                                }
                            }
                        }
                        HintText(stringResource(R.string.settings_keepalive_autostart_hint))
                    }
                }
            }

            // ── 外观 ──
            item {
                SettingsCard(stringResource(R.string.settings_group_appearance)) {
                    ClickRow(
                        title = stringResource(R.string.settings_theme),
                        value = themeLabel(settings.themeMode),
                        onClick = { showThemeDialog = true }
                    )
                    ClickRow(
                        title = stringResource(R.string.settings_language),
                        value = localeLabel(state.localeCode),
                        onClick = { showLanguageDialog = true }
                    )
                }
            }

            // ── 关于 ──
            item {
                SettingsCard(stringResource(R.string.settings_group_about)) {
                    ClickRow(
                        title = stringResource(R.string.settings_debug_log),
                        value = null,
                        onClick = onOpenDebugLog
                    )
                    HintText(stringResource(R.string.settings_debug_log_hint))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.settings_version),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = BuildConfig.VERSION_NAME,
                            style = MaterialTheme.typography.bodySmall,
                            color = inkMuted()
                        )
                    }
                    ClickRow(
                        title = stringResource(R.string.settings_project),
                        value = null,
                        onClick = { PermissionUtils.openUrl(context, PROJECT_URL) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    // ── 弹窗 ──

    if (showIntervalDialog) {
        ChoiceDialog(
            title = stringResource(R.string.settings_interval),
            options = AppSettings.INTERVAL_CHOICES.map { seconds ->
                Choice(
                    key = seconds.toString(),
                    label = intervalLabel(seconds),
                    selected = seconds == settings.intervalSeconds
                )
            },
            onPick = { key ->
                key.toIntOrNull()?.let { viewModel.setIntervalSeconds(it) }
                showIntervalDialog = false
            },
            onDismiss = { showIntervalDialog = false }
        )
    }

    if (showThemeDialog) {
        ChoiceDialog(
            title = stringResource(R.string.settings_theme),
            options = listOf(
                Choice(AppSettings.THEME_SYSTEM, stringResource(R.string.settings_theme_system), settings.themeMode == AppSettings.THEME_SYSTEM),
                Choice(AppSettings.THEME_LIGHT, stringResource(R.string.settings_theme_light), settings.themeMode == AppSettings.THEME_LIGHT),
                Choice(AppSettings.THEME_DARK, stringResource(R.string.settings_theme_dark), settings.themeMode == AppSettings.THEME_DARK),
            ),
            onPick = { mode ->
                viewModel.setThemeMode(mode)
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false }
        )
    }

    if (showLanguageDialog) {
        ChoiceDialog(
            title = stringResource(R.string.settings_language),
            options = listOf(
                Choice(LOCALE_SYSTEM, stringResource(R.string.settings_language_system), state.localeCode == null),
                Choice("zh", stringResource(R.string.settings_language_zh), state.localeCode == "zh"),
                Choice("zh-Hant", stringResource(R.string.settings_language_zh_hant), state.localeCode == "zh-Hant"),
                Choice("en", stringResource(R.string.settings_language_en), state.localeCode == "en"),
            ),
            onPick = { key ->
                viewModel.setLocale(if (key == LOCALE_SYSTEM) null else key)
                showLanguageDialog = false
                // Android 13+ 由 framework 自动重建 Activity；低版本必须手动重建才能重解析资源
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    (context as? Activity)?.recreate()
                }
            },
            onDismiss = { showLanguageDialog = false }
        )
    }

    // 全 App 唯一会删掉本机数据的入口：必须二次确认，且按钮做成红的（与「清空日志」同一范式）
    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text(stringResource(R.string.settings_history_clear_confirm)) },
            text = { Text(stringResource(R.string.settings_history_clear_body, state.historyCount)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearHistory()
                        showClearHistoryDialog = false
                    }
                ) { Text(stringResource(R.string.action_clear), color = StatusRed) }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

// ─── 通用小组件 ───

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = StrawberryPink
            )
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = inkMuted()
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ClickRow(
    title: String,
    value: String?,
    onClick: () -> Unit,
    /** value 的颜色；默认主题粉，保活那类「状态」行要按有没有达成染成绿/橙 */
    valueColor: Color = StrawberryPink,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        if (value != null) {
            Text(text = value, style = MaterialTheme.typography.bodySmall, color = valueColor)
        }
    }
}

@Composable
private fun KeepAliveActionRow(
    title: String,
    ok: Boolean,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(
                    if (ok) R.string.keepalive_state_ok else R.string.keepalive_state_missing
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) StatusGreen else StatusOrange
            )
        }
        TextButton(onClick = onAction) {
            Text(
                text = stringResource(
                    if (ok) R.string.keepalive_action_done else R.string.keepalive_action_grant
                ),
                color = StrawberryPink
            )
        }
    }
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = inkMuted(),
        modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
private fun TestConnectionBlock(state: SettingsUiState, onTest: () -> Unit) {
    val testing = state.testState == TestState.Testing
    val label: String? = when (val test = state.testState) {
        TestState.Idle -> null
        TestState.Testing -> stringResource(R.string.action_testing)
        is TestState.Ok -> stringResource(R.string.settings_test_ok, test.httpCode)
        is TestState.Unauthorized -> stringResource(R.string.settings_test_unauthorized)
        is TestState.Failed -> stringResource(R.string.settings_test_error, test.reason.asString())
        TestState.NotConfigured -> stringResource(R.string.settings_test_not_configured)
    }
    val color = when (state.testState) {
        is TestState.Ok -> StatusGreen
        TestState.Idle, TestState.Testing -> inkMuted()
        else -> StatusOrange
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = onTest,
            enabled = !testing,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = StrawberryPink,
                contentColor = Color.White
            )
        ) {
            Text(stringResource(R.string.action_test))
        }
        if (label != null) {
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = color,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<Choice>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { choice ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(choice.key) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = choice.selected, onClick = { onPick(choice.key) })
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = choice.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

// ─── 文案辅助 ───

@Composable
private fun intervalLabel(seconds: Int): String =
    if (seconds % 60 == 0) {
        stringResource(R.string.interval_minutes, seconds / 60)
    } else {
        stringResource(R.string.interval_seconds, seconds)
    }

@Composable
private fun themeLabel(mode: String): String = when (mode) {
    AppSettings.THEME_LIGHT -> stringResource(R.string.settings_theme_light)
    AppSettings.THEME_DARK -> stringResource(R.string.settings_theme_dark)
    else -> stringResource(R.string.settings_theme_system)
}

@Composable
private fun localeLabel(code: String?): String = when (code) {
    "zh" -> stringResource(R.string.settings_language_zh)
    "zh-Hant" -> stringResource(R.string.settings_language_zh_hant)
    "en" -> stringResource(R.string.settings_language_en)
    else -> stringResource(R.string.settings_language_system)
}