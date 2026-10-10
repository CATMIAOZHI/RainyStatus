package com.rainy.status.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rainy.status.R
import com.rainy.status.domain.model.DeviceSnapshot
import com.rainy.status.domain.model.MoodEmoji
import com.rainy.status.ui.components.resolve
import com.rainy.status.ui.theme.StatusGreen
import com.rainy.status.ui.theme.StatusOrange
import com.rainy.status.ui.theme.StrawberryPink
import com.rainy.status.ui.theme.inkMuted
import com.rainy.status.util.DurationFormatter
import com.rainy.status.util.PermissionUtils

/**
 * 首页：一眼看到「本机状态 + 上报是否正常 + 保活是否到位」。
 *
 * 用 LazyColumn 而不是 Column：保活卡片 3 行 + 心情输入框，小屏 / 大字体下必然超屏。
 *
 * 电量显示刻意读**实时采样**（[HomeViewModel.refreshSnapshot]）而不是最近一次上报值：
 * 用户打开 App 时想看的是此刻的电量，不是 10 分钟前发出去的那个数。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenMoodHistory: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }
    var moodText by remember { mutableStateOf("") }
    var moodEmoji by remember { mutableStateOf("") }
    /** 表情是否已到上限：跨上限那位正好切在代理对中间时结果只有 7 个单元，光看长度会漏提示 */
    var moodEmojiFull by remember { mutableStateOf(false) }
    var moodDraftSeeded by remember { mutableStateOf(false) }
    var showEndpointHint by remember { mutableStateOf(false) }

    // 草稿只在首帧灌入一次；之后完全由用户输入驱动，避免每帧被状态流覆盖。
    // 刻意放在 LaunchedEffect 里而不是组合期直接赋值：组合期写 state 会触发
    // 「组合中改状态」的重组，属于 Compose 明确不推荐的写法。
    LaunchedEffect(state.runtime.moodDraft, state.runtime.moodDraftEmoji, moodDraftSeeded) {
        if (moodDraftSeeded) return@LaunchedEffect
        val draft = state.runtime.moodDraft
        val draftEmoji = state.runtime.moodDraftEmoji
        // 只要有一个有草稿就灌入：只填了表情、文字还空着时，文字草稿存的是「无」
        // （空串按无草稿处理），但那个表情不该跟着一起丢
        if (draft != null || draftEmoji != null) {
            moodText = draft.orEmpty()
            moodEmoji = draftEmoji.orEmpty()
            moodEmojiFull = MoodEmoji.isFull(moodEmoji)
            moodDraftSeeded = true
        }
    }

    // 只有「发送成功」才清空输入框（ViewModel 用计数通知）：失败时清空会把用户刚打的字丢掉。
    // 刻意**不**重置 moodDraftSeeded：清空后草稿已落盘为 ""，若把 seed 标记放回 false，
    // 上方那个效果会拿着状态流里还没更新完的旧草稿把刚发出去的文字重新灌回输入框。
    LaunchedEffect(state.moodSentCount) {
        if (state.moodSentCount > 0) {
            moodText = ""
            moodEmoji = ""
            moodEmojiFull = false
        }
    }

    // 从系统设置页回来时刷新保活状态与实时电量（ON_RESUME 不会走 ViewModel 的 init）
    LifecycleResumeEffect(Unit) {
        viewModel.refreshKeepAlive()
        viewModel.refreshSnapshot()
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

    if (showEndpointHint) {
        AlertDialog(
            onDismissRequest = { showEndpointHint = false },
            confirmButton = {
                TextButton(onClick = { showEndpointHint = false }) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            title = { Text(stringResource(R.string.home_view_page_missing)) },
            text = { Text(stringResource(R.string.settings_endpoint_hint)) }
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.home_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = stringResource(R.string.cd_open_settings),
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
            item { StatusCard(state) }

            item {
                ActionRow(
                    onReportNow = { viewModel.reportNow() },
                    onOpenStatusPage = {
                        val url = viewModel.statusPageUrl()
                        if (url == null) showEndpointHint = true
                        else PermissionUtils.openUrl(context, url)
                    },
                    reporting = state.reporting,
                )
            }

            item {
                HistoryCard(
                    samples = state.history,
                    historyEnabled = state.settings.historyEnabled,
                    onEnableHistory = { viewModel.setHistoryEnabled(true) },
                )
            }

            if (state.settings.moodEnabled) {
                item {
                    MoodCard(
                        text = moodText,
                        emoji = moodEmoji,
                        emojiFull = moodEmojiFull,
                        onTextChange = { value ->
                            moodText = value
                            moodDraftSeeded = true
                            viewModel.saveMoodDraft(value, moodEmoji)
                        },
                        onEmojiChange = { value ->
                            // 按与云端相同的口径截断（UTF-16 ≤ 8）：超出部分在这里就掉，
                            // 免得用户打完一大串才发现发不出去
                            moodEmoji = MoodEmoji.sanitize(value)
                            // 提示判据用**原始输入**：跨上限那位正好切在代理对中间时，
                            // 截断结果只有 7 个单元，只看长度会漏掉提示
                            moodEmojiFull = MoodEmoji.isFull(value)
                            moodDraftSeeded = true
                            viewModel.saveMoodDraft(moodText, moodEmoji)
                        },
                        onSend = {
                            // 不在这里清空输入框：失败时要保留用户打的字（见上方的 moodSentCount 效果）
                            viewModel.sendMood(moodText, moodEmoji)
                        }
                    )
                }
            }

            // ── 最近的心情 ──
            // 只在本机记录开关打开时出现；关着就整块不渲染（设置页已经有那个开关，
            // 而且设置页那行「已记录 N 条」始终可以点进历史页，旧记录不会因此变成孤儿）。
            // 卡片只给最近 3 条，底部的「查看全部」通向按天分组的完整历史。
            if (state.settings.moodHistoryEnabled) {
                item { MoodRecentCard(state.moodHistory, onOpenAll = onOpenMoodHistory) }
            }

            // ── 保活检查 ──
            // 刻意放在最下面：这是「装好之后折腾一次」的检查项，平时不该挡在
            // 电量 / 上报 / 心情这些每天都要看的卡片前面。
            item {
                KeepAliveCard(
                    status = state.keepAlive,
                    autostartConfirmed = state.settings.autostartConfirmed,
                    onOpenSettings = onOpenSettings,
                    onConfirmAutostart = { viewModel.confirmAutostart(it) },
                )
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

// ─── 状态卡片 ───

@Composable
private fun StatusCard(state: HomeUiState) {
    val context = LocalContext.current
    val settings = state.settings
    val runtime = state.runtime
    val snapshot = state.snapshot
    val battery = if (settings.includeBattery) snapshot?.batteryPercent else null
    val charging = settings.includeCharging && snapshot?.charging == true
    val batteryDescription = battery?.let { stringResource(R.string.cd_battery_progress, it) }.orEmpty()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            StateLine(state)

            if (battery != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = battery.toString(),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "%",
                        style = MaterialTheme.typography.titleMedium,
                        color = inkMuted(),
                        modifier = Modifier.padding(start = 2.dp, bottom = 4.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = chargeLabel(settings.includeCharging, snapshot),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (charging) StatusGreen else inkMuted(),
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { battery / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        // 进度条本身不带文字，读屏用户只能听到「进度条」；
                        // 显式给语义描述，否则这一整块电量信息对他们等于不存在
                        .semantics { contentDescription = batteryDescription },
                    color = StrawberryPink,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            } else if (!settings.includeBattery) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.home_battery_hidden),
                    style = MaterialTheme.typography.bodySmall,
                    color = inkMuted()
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (settings.includeTemperature) {
                InfoRow(
                    label = stringResource(R.string.home_temperature),
                    // 读不到温度时不显示这一行（InfoRow 对 null 会整行跳过），不显示假的 0.0 °C
                    value = snapshot?.temperatureC?.let {
                        context.getString(R.string.home_temperature_value, it)
                    }
                )
            }
            if (settings.includeNetwork) {
                InfoRow(
                    label = stringResource(R.string.home_network),
                    value = snapshot?.let { networkLabel(it.network) }
                )
            }
            InfoRow(
                label = stringResource(R.string.home_last_success),
                value = if (runtime.lastSuccessAt > 0L) {
                    DurationFormatter.format(context, System.currentTimeMillis() - runtime.lastSuccessAt)
                } else {
                    stringResource(R.string.home_never_reported)
                }
            )
            InfoRow(
                label = stringResource(R.string.settings_interval),
                value = intervalLabel(settings.intervalSeconds)
            )
        }
    }
}

/**
 * 顶部状态摘要。
 *
 * 优先级：未配置 > Token 失效 > 其他上报失败 > 降频 > 运行中。
 *
 * 为什么必须有「其他上报失败」这一档：`lastErrorKind` 会被写入**所有**失败分类，
 * 但以前只识别 `Unauthorized`，于是地址填错（404）、服务端 5xx、DNS 失败等情况下
 * 首页仍显示绿色「正常」+ 一条早已过期的上次上报时间。
 * 这个 App 的全部意义就是「让水晴知道手机还活着」，心跳停了却报绿色最伤信任。
 */
@Composable
private fun StateLine(state: HomeUiState) {
    val settings = state.settings
    val errorKind = state.runtime.lastErrorKind
    val (dotColor, textRes) = when {
        !settings.configured -> StatusOrange to R.string.home_state_not_configured
        errorKind == "Unauthorized" -> StatusOrange to R.string.home_state_auth_error
        errorKind != null -> StatusOrange to R.string.home_state_report_error
        state.throttled -> StatusOrange to R.string.home_state_throttled
        settings.enabled -> StatusGreen to R.string.home_state_ok
        else -> StatusOrange to R.string.home_service_stopped
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Card(
            shape = RoundedCornerShape(50),
            colors = CardDefaults.cardColors(containerColor = dotColor),
            modifier = Modifier.size(10.dp)
        ) { }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String?) {
    if (value == null) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = inkMuted(),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// ─── 动作 ───

@Composable
private fun ActionRow(
    onReportNow: () -> Unit,
    onOpenStatusPage: () -> Unit,
    reporting: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = onReportNow,
            // 防连点：MANUAL 绕过一切节流，连点等于真写多次 KV
            enabled = !reporting,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = StrawberryPink,
                contentColor = Color.White
            )
        ) {
            Text(stringResource(R.string.action_report_now))
        }
        Button(
            onClick = onOpenStatusPage,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = StrawberryPink
            )
        ) {
            Text(stringResource(R.string.home_view_page))
        }
    }
}

// ─── 保活 ───

@Composable
private fun KeepAliveCard(
    status: KeepAliveStatus,
    /** 用户自述已在系统里打开「自启动」，不是系统状态检测结果。 */
    autostartConfirmed: Boolean,
    onOpenSettings: () -> Unit,
    onConfirmAutostart: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    // 自启动只在需要它的厂商上参与判定：原生 Android 没这个开关，
    // 把它算进 allGood 会让原生机器永远显示「还有一项没做」。
    val allGood = status.ignoringBatteryOptimizations &&
        status.canScheduleExactAlarms &&
        status.hasNotificationPermission &&
        (!status.needsAutostartGuide || autostartConfirmed)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.home_keepalive_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(10.dp))

            KeepAliveRow(
                label = stringResource(R.string.home_keepalive_battery),
                ok = status.ignoringBatteryOptimizations,
                okText = stringResource(R.string.home_keepalive_battery_ok),
                offText = stringResource(R.string.home_keepalive_battery_no),
                onFix = { PermissionUtils.openBatteryOptimizationSettings(context) }
            )
            KeepAliveRow(
                label = stringResource(R.string.home_keepalive_alarm),
                ok = status.canScheduleExactAlarms,
                okText = stringResource(R.string.home_keepalive_alarm_ok),
                offText = stringResource(R.string.home_keepalive_alarm_no),
                onFix = { PermissionUtils.openExactAlarmSettings(context) }
            )
            KeepAliveRow(
                label = stringResource(R.string.home_keepalive_notification),
                ok = status.hasNotificationPermission,
                okText = stringResource(R.string.home_keepalive_notification_ok),
                offText = stringResource(R.string.home_keepalive_notification_no),
                // 通知权限要走运行时请求（需要 Activity），这里跳到设置页由它发起，不假装一键完成
                onFix = onOpenSettings
            )

            if (status.needsAutostartGuide) {
                AutostartRow(
                    confirmed = autostartConfirmed,
                    onOpen = { PermissionUtils.openAutostartSettings(context) },
                    onConfirm = onConfirmAutostart,
                )
            }

            if (!allGood) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.home_keepalive_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = inkMuted()
                )
            }
        }
    }
}

/**
 * 「系统自启动」一行。
 *
 * 本项没有使用系统状态查询接口：只展示可撤销的用户确认。
 * 跳转设置不自动确认；撤销只清除本地记录，不会关闭系统开关。
 */
@Composable
private fun AutostartRow(
    confirmed: Boolean,
    onOpen: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.home_keepalive_autostart),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(
                    if (confirmed) R.string.home_keepalive_autostart_ok
                    else R.string.home_keepalive_autostart_no
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (confirmed) StatusGreen else StatusOrange
            )
        }
        TextButton(onClick = onOpen) {
            Text(
                text = stringResource(
                    if (confirmed) R.string.keepalive_action_open
                    else R.string.keepalive_action_grant
                ),
                color = StrawberryPink
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (confirmed) {
            Text(
                text = stringResource(R.string.keepalive_action_revoke),
                style = MaterialTheme.typography.bodySmall,
                color = inkMuted()
            )
            IconButton(onClick = { onConfirm(false) }) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.keepalive_action_revoke),
                    tint = StrawberryPink
                )
            }
        } else {
            TextButton(onClick = { onConfirm(true) }) {
                Text(stringResource(R.string.keepalive_action_confirm), color = StrawberryPink)
            }
        }
    }
}

@Composable
private fun KeepAliveRow(
    label: String,
    ok: Boolean,
    okText: String,
    offText: String,
    onFix: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (ok) okText else offText,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) StatusGreen else StatusOrange
            )
        }
        if (!ok) {
            TextButton(onClick = onFix) {
                Text(stringResource(R.string.keepalive_action_grant), color = StrawberryPink)
            }
        }
    }
}

// ─── 心情 ───

@Composable
private fun MoodCard(
    text: String,
    emoji: String,
    /** 是否已达上限：由调用方用「原始输入」判定（见 MoodEmoji.isFull 的注释） */
    emojiFull: Boolean,
    onTextChange: (String) -> Unit,
    onEmojiChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.home_mood_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(10.dp))
            // 表情与文案分开填：表情是网页上状态行前面那个小图标，文案是那句话。
            // 刻意不做固定候选列表——用系统输入法的 emoji 面板随便挑，
            // 固定列表只会变成「想要的表情它没有」。
            val emojiHint = stringResource(R.string.home_mood_emoji_hint)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = emoji,
                    onValueChange = onEmojiChange,
                    modifier = Modifier.width(88.dp),
                    // 这里**不能放 emoji 占位符**：空框里躺着一个 😊，看起来就是
                    // 「已经默认选好表情了」，会被当成 bug（真发生过）。
                    // 换成浮动 label：没填时框里显示「表情」，填了才浮到边上去。
                    // label 本身就能被 TalkBack 念出来，不需要再手写 contentDescription。
                    label = { Text(stringResource(R.string.home_mood_emoji_label)) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
                    shape = RoundedCornerShape(14.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    // 满了以后多打的表情会被静默截断（看起来就是「打不进去」），
                    // 换成一句提示，免得用户以为键盘坏了
                    text = if (emojiFull) stringResource(R.string.home_mood_emoji_full) else emojiHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (emojiFull) StatusOrange else inkMuted()
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.home_mood_hint)) },
                maxLines = 3,
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${text.codePointCount(0, text.length)} / ${HomeViewModel.MAX_MOOD_CODEPOINTS}",
                    style = MaterialTheme.typography.labelSmall,
                    color = inkMuted(),
                    modifier = Modifier.padding(end = 12.dp)
                )
                Button(
                    onClick = onSend,
                    // 刻意**不**在空文案时置灰：云端 text 是必填的，置灰虽然拦得对，
                    // 但用户只挑了表情时只会看到「点不动的按钮」，不知道少了什么。
                    // 点下去由 ViewModel 弹「先写点什么吧」，顺便把这条文案用起来。
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StrawberryPink,
                        contentColor = Color.White
                    )
                ) {
                    Text(stringResource(R.string.home_mood_send))
                }
            }
        }
    }
}

// ─── 文案辅助 ───

@Composable
private fun chargeLabel(includeCharging: Boolean, snapshot: DeviceSnapshot?): String {
    if (!includeCharging || snapshot == null) return stringResource(R.string.home_charging_unknown)
    if (!snapshot.charging) return stringResource(R.string.home_not_charging)
    return when (snapshot.chargeSource) {
        DeviceSnapshot.SOURCE_AC -> stringResource(R.string.home_charge_source_ac)
        DeviceSnapshot.SOURCE_USB -> stringResource(R.string.home_charge_source_usb)
        DeviceSnapshot.SOURCE_WIRELESS -> stringResource(R.string.home_charge_source_wireless)
        else -> stringResource(R.string.home_charging)
    }
}

@Composable
private fun networkLabel(network: String): String = when (network) {
    DeviceSnapshot.NET_WIFI -> stringResource(R.string.home_network_wifi)
    DeviceSnapshot.NET_CELLULAR -> stringResource(R.string.home_network_cellular)
    DeviceSnapshot.NET_ETHERNET -> stringResource(R.string.home_network_ethernet)
    DeviceSnapshot.NET_NONE -> stringResource(R.string.home_network_none)
    else -> stringResource(R.string.home_network_unknown)
}

@Composable
private fun intervalLabel(seconds: Int): String =
    if (seconds % 60 == 0) {
        stringResource(R.string.interval_minutes, seconds / 60)
    } else {
        stringResource(R.string.interval_seconds, seconds)
    }