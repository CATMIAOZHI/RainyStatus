package com.rainy.status.ui.moodhistory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rainy.status.R
import com.rainy.status.domain.history.MoodDay
import com.rainy.status.domain.history.MoodDayLabel
import com.rainy.status.domain.history.MoodEvent
import com.rainy.status.domain.history.MoodTimeline
import com.rainy.status.ui.components.resolve
import com.rainy.status.ui.theme.StatusRed
import com.rainy.status.ui.theme.StrawberryPink
import com.rainy.status.ui.theme.inkMuted
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 心情历史页：本机存过的**全部**心情，按天倒着排。
 *
 * 存在的理由是「首页只能给你最近 3 条，那 4 条以前的去哪了」——设置页那个数字一直数得出来，
 * 却没有任何地方能看到它们。这一页就是那个地方。
 *
 * 几条刻意定下的规矩：
 * 1. **一次读全量，不做分页**。本机 rowid（`at`）有序表，几百条到几万条都是一次顺序读；
 *    而分页会让「设置页写着已记录 312 条 / 这里只能刷出 50 条」这种数字对不上的事发生——
 *    日记类数据里，这是最贵的一类错误。
 * 2. **文案完整显示**，不再像首页卡片那样单行省略：这里就是来看全的。
 * 3. 时间口径是「日期在组头、时刻在行内」：组头回答「哪一天」，行内回答「几点」，
 *    相对时间（刚刚 / N 分钟前）只留在首页卡片上——翻旧账时「3 个月前」没有意义。
 * 4. **不做单条删除**。删掉本机这一条，云端 `history_mood` 与状态页上那条都还在，
 *    给了删除按钮只会让人以为「删干净了」，比没有更危险。要清就走右上角的「清空心情记录」，
 *    那个按钮的对话框会把「电量历史不受影响、状态页那条也不变」说清楚。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodHistoryScreen(
    onBack: () -> Unit,
    viewModel: MoodHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }
    var showClearConfirm by remember { mutableStateOf(false) }

    // 分组按**设备本地时区**的日历日。这里刻意只在这里取一次「现在」：
    // 分组只用到「今天是哪一天」，列表一变会重算，停在页面上跨零点的人最多看到一个
    // 过时的「今天」组头，不会出现「明天的组头」。
    val zone = remember { ZoneId.systemDefault() }
    val days = remember(state.events, zone) {
        MoodTimeline.groupByDay(state.events, System.currentTimeMillis(), zone)
    }
    // 时刻格式从资源里取：中文 24 小时制、英文 12 小时制（h:mm a），两种都不该硬编码
    val locale = LocalContext.current.resources.configuration.locales[0]
    val timePattern = stringResource(R.string.mood_history_time_format)
    val timeFormatter = remember(timePattern, zone) {
        DateTimeFormatter.ofPattern(timePattern, locale).withZone(zone)
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHost.showSnackbar(message.resolve(context))
        viewModel.consumeMessage()
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.settings_mood_history_clear_confirm)) },
            text = { Text(stringResource(R.string.settings_mood_history_clear_body, state.events.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clear()
                        showClearConfirm = false
                    }
                ) {
                    Text(stringResource(R.string.action_clear), color = StatusRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.mood_history_title),
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
                actions = {
                    // 空列表时不给按钮：没有东西可清，按了只会弹一个「将删掉 0 条」的对话框
                    if (state.events.isNotEmpty()) {
                        TextButton(onClick = { showClearConfirm = true }) {
                            // 这里用 2 个字的 action_clear（与 DebugLogScreen 一致），不用设置页那条
                            // 「清空心情记录」：6 个汉字在大字体下会把标题挤成「心情…」，甚至换行。
                            // 「清空什么」由对话框标题说清楚（settings_mood_history_clear_confirm）
                            Text(stringResource(R.string.action_clear), color = StrawberryPink)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 开关关掉但还有旧记录：列表照常显示，只是说明一句「为什么没有新的了」。
            // 关开关不等于删数据——不说的话，用户会以为关掉就把记录清空了。
            // **列表为空时也要显示**：刚清空过的人会分不清「是清空了还是开关坏了」，
            // 这句话正好回答「记录已关闭，之前存下的（现在没有了）」
            if (!state.recordingEnabled) {
                Text(
                    // 列表为空时换一句：上面那句「只显示之前存下的记录」会让人以为「还有旧记录没显示」，
                    // 而下面紧接着就是「还没有心情记录」，两句放一起会让人怀疑自己发过的被漏记了
                    text = stringResource(
                        if (state.events.isEmpty()) {
                            R.string.mood_history_recording_off_empty
                        } else {
                            R.string.mood_history_recording_off
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = inkMuted(),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            if (state.events.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.mood_history_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = inkMuted()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.mood_history_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = inkMuted(),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    days.forEach { day ->
                        // 组头用普通 item 而不是 stickyHeader：这里一天也就几条，
                        // 吸顶的横条在大字体下会占掉一大块，反而更挤
                        item(key = "day_${day.dayStartMs}") { DayHeader(day) }
                        items(day.events, key = { it.at }) { event ->
                            MoodEntryCard(event, timeFormatter)
                        }
                    }
                    item(key = "footer") {
                        Text(
                            // 页脚那句「共 N 条」是刻意的：它是唯一能让用户拿它和设置页
                            // 那个数字对上号的地方，省掉「我是不是丢记录了」的猜疑
                            text = stringResource(R.string.mood_history_total, state.events.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = inkMuted(),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 组头：今天 / 昨天 / 10月8日 / 2025年12月31日 */
@Composable
private fun DayHeader(day: MoodDay) {
    val locale = LocalContext.current.resources.configuration.locales[0]
    val text = when (day.label) {
        MoodDayLabel.TODAY -> stringResource(R.string.mood_history_today)
        MoodDayLabel.YESTERDAY -> stringResource(R.string.mood_history_yesterday)
        // 跨年那天必须带上年份：只写「12月31日」会和今年的 12 月 31 日撞车
        MoodDayLabel.SAME_YEAR -> day.date.format(
            DateTimeFormatter.ofPattern(stringResource(R.string.mood_history_day_format), locale)
        )
        MoodDayLabel.EARLIER_YEAR -> day.date.format(
            DateTimeFormatter.ofPattern(stringResource(R.string.mood_history_day_format_year), locale)
        )
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = inkMuted(),
        modifier = Modifier.padding(start = 4.dp, top = 12.dp)
    )
}

/**
 * 一条心情：表情 + 完整文字，时间另起一行右对齐。
 *
 * 时间不挤在正文右边，是为了 2.0× 字体下的长文案：并排的话两边都会压缩到只剩几个字，
 * 上下排则各自完整。**不写 maxLines**——这一页就是来看全文的。
 */
@Composable
private fun MoodEntryCard(event: MoodEvent, timeFormatter: DateTimeFormatter) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 14.dp)
                // 一条记录一次朗读：不合并的话读屏会先念表情、再念文字、再念时间，像三条
                .semantics(mergeDescendants = true) {}
        ) {
            Row(verticalAlignment = Alignment.Top) {
                val emoji = event.emoji
                // 不给表情留固定槽位：没表情的记录会白白空出一块，而「这一条是什么时候说的」
                // 才是往下扫的时候真正在对齐的东西（时间统一右对齐）
                if (!emoji.isNullOrEmpty()) {
                    Text(text = emoji, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = event.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = timeFormatter.format(Instant.ofEpochMilli(event.at)),
                style = MaterialTheme.typography.labelSmall,
                color = inkMuted(),
                textAlign = TextAlign.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            )
        }
    }
}