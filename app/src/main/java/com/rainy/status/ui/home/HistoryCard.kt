package com.rainy.status.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rainy.status.R
import com.rainy.status.domain.history.BatteryHistory
import com.rainy.status.domain.history.BatterySample
import com.rainy.status.domain.history.DayRange
import com.rainy.status.domain.history.HistoryRange
import com.rainy.status.ui.theme.StatusGreen
import com.rainy.status.ui.theme.StrawberryPink
import com.rainy.status.ui.theme.inkMuted
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 本机电量历史卡片。
 *
 * 与网页图表是同一套口径，只是数据源换成「本机记的 7 天」：
 * - **24 小时**：折线（断档不连线）+ 充电绿带 + 一行汇总；
 * - **7 天**：每天一根「最低–最高」区间条 + 每天充电次数（数字）。
 *
 * 刻意不做的东西：
 * - 不做周/月**平均电量**（电量是循环量，平均没有意义，见 `docs/roadmap.md`）；
 * - 不引任何图表库，Canvas 手绘（只有两种图形，库带来的体积与 API 风险不划算）；
 * - 文字一律走 Compose 的 Text 而不是 Canvas 内绘制：这样能跟随系统字号与语言，
 *   不会出现「图表里的字比别处小一号」。
 */
@Composable
fun HistoryCard(
    samples: List<BatterySample>,
    /** 本地记录开关；关着时卡片只显示「未开启」与开启按钮 */
    historyEnabled: Boolean,
    onEnableHistory: () -> Unit,
) {
    // 0 = 24 小时，1 = 7 天。存 Int 而不是枚举：rememberSaveable 对枚举要额外包一层。
    var rangeIndex by rememberSaveable { mutableStateOf(0) }
    val range = if (rangeIndex == 1) HistoryRange.D7 else HistoryRange.H24

    // 每次重组取一次「现在」：图表窗口、横轴标签都以它为基准。
    // 不 remember：它本来就该随重组刷新，缓存住反而会出现「停在旧时刻」的窗口。
    val now = System.currentTimeMillis()
    // 时区只在进入页面时取一次：切系统时区会重建 Activity，不需要在这里动态跟随
    val zone = remember { ZoneId.systemDefault() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.home_history_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                // 时间档切换用带标签的小胶囊：24 小时 / 7 天 没有能一眼看懂的图标，
                // 这里不用图标不是遗漏（对比设置页的显隐开关——那里眼睛图标才是更清楚的表达）。
                if (historyEnabled) {
                    RangeChip(
                        label = stringResource(R.string.home_history_range_24h),
                        selected = range == HistoryRange.H24,
                        onClick = { rangeIndex = 0 }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    RangeChip(
                        label = stringResource(R.string.home_history_range_7d),
                        selected = range == HistoryRange.D7,
                        onClick = { rangeIndex = 1 }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            when {
                !historyEnabled -> HistoryDisabled(onEnableHistory)
                range == HistoryRange.H24 -> LineSection(samples, now, zone)
                else -> DailySection(samples, now, zone)
            }
        }
    }
}

// ─── 24 小时：折线 ───

@Composable
private fun LineSection(samples: List<BatterySample>, now: Long, zone: ZoneId) {
    val from = now - BatteryHistory.H24_MS
    val window = BatteryHistory.inWindow(samples, from, now)
    val points = window.filter { it.b != null }
    val latest = points.lastOrNull()?.b
    val minMax = BatteryHistory.minMax(points)
    if (latest == null || minMax == null) {
        HistoryEmpty()
        return
    }

    val low = minMax.first
    val high = minMax.second
    val sessions = BatteryHistory.chargeSessions(window, BatteryHistory.chargingStateBefore(samples, from))
    // 横轴从「窗口起点」和「第一个点」里取晚的那个：
    // 刚打开本地记录时只有一两个点，若强行按满 24 小时铺开，曲线会缩成右边缘的一个小点，
    // 看上去像坏了。窗口语义不变（只取最近 24 小时的数据），只是把空白的左边不画出来。
    val axisFrom = maxOf(from, points.first().t)
    val description = stringResource(R.string.cd_history_chart, low, high)
    val lineColor = StrawberryPink
    val bandColor = StatusGreen.copy(alpha = 0.20f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(CHART_HEIGHT)
            .semantics { contentDescription = description }
    ) {
        drawGrid(gridColor)
        drawChargeBands(window, axisFrom, now, bandColor)
        drawBatteryLine(points, axisFrom, now, lineColor)
    }

    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(top = 6.dp)) {
        Text(timeLabel(axisFrom, zone), style = MaterialTheme.typography.labelSmall, color = inkMuted())
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = timeLabel(axisFrom + (now - axisFrom) / 2, zone),
            style = MaterialTheme.typography.labelSmall,
            color = inkMuted()
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.home_history_axis_now),
            style = MaterialTheme.typography.labelSmall,
            color = inkMuted()
        )
    }

    Text(
        text = stringResource(R.string.home_history_summary_24h, latest, low, high, sessions),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 8.dp)
    )
    Text(
        text = stringResource(R.string.home_history_legend_24h),
        style = MaterialTheme.typography.labelSmall,
        color = inkMuted(),
        modifier = Modifier.padding(top = 4.dp)
    )
}

// ─── 7 天：每日区间条 ───

@Composable
private fun DailySection(samples: List<BatterySample>, now: Long, zone: ZoneId) {
    val days = BatteryHistory.dailyRanges(samples, now, zone)
    if (days.none { it.hasData }) {
        HistoryEmpty()
        return
    }

    val low = days.mapNotNull { it.min }.minOrNull() ?: 0
    val high = days.mapNotNull { it.max }.maxOrNull() ?: 0
    val description = stringResource(R.string.cd_history_chart, low, high)
    val barColor = StrawberryPink
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(CHART_HEIGHT)
            .semantics { contentDescription = description }
    ) {
        drawGrid(gridColor)
        drawDailyBars(days, barColor)
    }

    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(top = 6.dp)) {
        days.forEach { day ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = dayLabel(day.dayStartMs, zone),
                    style = MaterialTheme.typography.labelSmall,
                    color = inkMuted()
                )
                // 数字对齐在各自那一列下面，肉眼就能对上是哪一天：
                // 没有数据的那天不显示 0（0 次 ≠ 不知道），用「—」区分开
                Text(
                    text = if (day.hasData) {
                        stringResource(R.string.home_history_day_count, day.chargeSessions)
                    } else {
                        stringResource(R.string.home_history_no_value)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (day.hasData) barColor else inkMuted()
                )
            }
        }
    }

    Text(
        text = stringResource(R.string.home_history_legend_7d),
        style = MaterialTheme.typography.labelSmall,
        color = inkMuted(),
        modifier = Modifier.padding(top = 8.dp)
    )
}

// ─── 绘制 ───

private val CHART_HEIGHT = 140.dp

/** 绘制区内边距：0% 与 100% 的点不能压在边框上，否则线宽会被裁掉一半 */
private val CHART_INSET = 6.dp

private fun DrawScope.drawGrid(gridColor: Color) {
    val inset = CHART_INSET.toPx()
    listOf(0, 25, 50, 75, 100).forEach { level ->
        val y = yFor(level, size.height, inset)
        drawLine(
            color = gridColor,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1.dp.toPx()
        )
    }
}

private fun DrawScope.drawChargeBands(
    window: List<BatterySample>,
    from: Long,
    to: Long,
    bandColor: Color,
) {
    val inset = CHART_INSET.toPx()
    val plotHeight = size.height - inset * 2
    BatteryHistory.chargeBands(window).forEach { band ->
        val left = xFor(band.first, from, to, size.width)
        val right = xFor(band.last, from, to, size.width)
        drawRect(
            color = bandColor,
            topLeft = Offset(left, inset),
            // 只有一个样本点（或刚好落在同像素）时给一个最小宽度，否则这条充电记录就看不见了
            size = Size((right - left).coerceAtLeast(2.dp.toPx()), plotHeight)
        )
    }
}

private fun DrawScope.drawBatteryLine(
    points: List<BatterySample>,
    from: Long,
    to: Long,
    lineColor: Color,
) {
    val inset = CHART_INSET.toPx()
    val stroke = 2.dp.toPx()
    BatteryHistory.segments(points, BatteryHistory.LINE_GAP_MS).forEach { segment ->
        if (segment.size == 1) {
            val only = segment.first()
            val value = only.b
            if (value != null) {
                drawCircle(
                    color = lineColor,
                    radius = stroke,
                    center = Offset(xFor(only.t, from, to, size.width), yFor(value, size.height, inset))
                )
            }
        } else {
            val path = Path()
            segment.forEachIndexed { index, sample ->
                val value = sample.b ?: return@forEachIndexed
                val x = xFor(sample.t, from, to, size.width)
                val y = yFor(value, size.height, inset)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

private fun DrawScope.drawDailyBars(days: List<DayRange>, barColor: Color) {
    if (days.isEmpty()) return
    val inset = CHART_INSET.toPx()
    val slot = size.width / days.size
    val barWidth = minOf(slot - 8.dp.toPx(), 20.dp.toPx()).coerceAtLeast(6.dp.toPx())
    // 圆角最多 6dp。**不能**用 barWidth / 2：某天电量区间很窄时（比如只充了一会儿电），
    // 柱子高度会小于半宽，圆角一夹就成了一个圆球——看着像「一个点」而不是「一段区间」。
    val barRadius = minOf(barWidth / 2f, 6.dp.toPx())
    days.forEachIndexed { index, day ->
        val min = day.min ?: return@forEachIndexed
        val max = day.max ?: return@forEachIndexed
        val centerX = slot * index + slot / 2f
        val top = yFor(max, size.height, inset)
        val bottom = yFor(min, size.height, inset)
        drawRoundRect(
            color = barColor,
            topLeft = Offset(centerX - barWidth / 2f, top),
            // 当天电量没变（min == max）时给一个最小高度，否则柱子是一条看不见的零高矩形
            size = Size(barWidth, (bottom - top).coerceAtLeast(4.dp.toPx())),
            cornerRadius = CornerRadius(barRadius)
        )
    }
}

/** 电量百分比 → 绘制区 y 坐标（0% 在下、100% 在上） */
private fun yFor(percent: Int, height: Float, inset: Float): Float =
    inset + (1f - percent.coerceIn(0, 100) / 100f) * (height - inset * 2)

/** 时刻 → 绘制区 x 坐标 */
private fun xFor(t: Long, from: Long, to: Long, width: Float): Float {
    if (to <= from) return 0f
    return ((t - from).toDouble() / (to - from).toDouble() * width).toFloat()
}

// ─── 小部件 ───

@Composable
private fun RangeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) StrawberryPink else MaterialTheme.colorScheme.surfaceVariant)
            // 用 selectable 而不是 clickable：这样读屏能念出「已选中」，
            // 否则两个胶囊听起来一模一样，用户不知道当前是哪个档位
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Color.White else inkMuted()
        )
    }
}

@Composable
private fun HistoryDisabled(onEnable: () -> Unit) {
    Text(
        text = stringResource(R.string.home_history_disabled),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Text(
        text = stringResource(R.string.home_history_disabled_hint),
        style = MaterialTheme.typography.bodySmall,
        color = inkMuted(),
        modifier = Modifier.padding(top = 4.dp)
    )
    Button(
        onClick = onEnable,
        // 默认 Button 的横向内边距在「卡片里的小动作」上显得很空，收紧一点
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = StrawberryPink,
            contentColor = Color.White
        ),
        modifier = Modifier.padding(top = 10.dp)
    ) {
        Text(stringResource(R.string.home_history_enable))
    }
}

@Composable
private fun HistoryEmpty() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.home_history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.home_history_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = inkMuted(),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

// ─── 时间标签 ───

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_FORMAT = DateTimeFormatter.ofPattern("M/d")

private fun timeLabel(t: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(t).atZone(zone).format(TIME_FORMAT)

private fun dayLabel(dayStartMs: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(dayStartMs).atZone(zone).format(DAY_FORMAT)