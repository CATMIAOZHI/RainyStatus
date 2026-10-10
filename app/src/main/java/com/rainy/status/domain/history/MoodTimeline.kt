package com.rainy.status.domain.history

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 一条心情事件（本机记的「我发过什么」）。
 *
 * 字段与云端 `history_mood` 表同口径（`updated_at / text / emoji`），但**别指望两边条数一致**，
 * 至少三种情况必然错位：响应丢了但服务端已经写成功、内容没变时 App 直接跳过发送（两边都不写）、
 * 清空本机记录之后再发一次。
 */
data class MoodEvent(
    /** 事件时刻（epoch ms UTC）。用服务端返回的 `updatedAt`，见 [MoodTimeline.recordedAt] */
    val at: Long,
    val text: String,
    /** 没有表情时统一 `null`（不写空串），与云端 `emoji` 列同口径 */
    val emoji: String? = null,
)

/** 组头该写成什么。纯逻辑只给「种类 + 日期」，具体文案由 UI 层按当前语言渲染。 */
enum class MoodDayLabel {
    TODAY, YESTERDAY, SAME_YEAR, EARLIER_YEAR
}

/** 心情历史里的一天。 */
data class MoodDay(
    /** 这个本地日的 00:00（epoch ms）；同时拿来当列表 key，同一天不会有第二个 */
    val dayStartMs: Long,
    val label: MoodDayLabel,
    val date: LocalDate,
    val events: List<MoodEvent>,
)

/**
 * 心情历史的纯逻辑（不碰 Room 与 Android API，方便单测）。
 *
 * 保留期与电量一致：**永久**，没有自动清理，只有用户手动清空。
 * 刻意不做保留期：真正伤人的是最近发的那几条，删旧的等于没删；
 * 真正的隐私杠杆在别处——只在本机、默认开（数据不出设备，2026-10 决定）、清空入口与电量分开。
 */
object MoodTimeline {

    /** 首页「最近的心情」最多显示几条 */
    const val MAX_RECENT = 3

    /**
     * 记哪个时刻：优先服务端返回的 `updatedAt`，缺失或非法才退回本机成功时刻。
     *
     * 不一律用本机时钟的原因不是「更准」，而是**别把两个时间源混进同一列**：
     * 这个值同时也是云端 `history_mood.updated_at` 与网页 `data.mood[].at`，
     * 用它才能和网页逐条对上；而且设备时钟被改坏（或跨时区）时列表不会出现
     * 「新记录排在旧记录下面」。在线路径下两者差的就是一个 RTT。
     */
    fun recordedAt(serverUpdatedAt: Long?, localNow: Long): Long =
        serverUpdatedAt?.takeIf { it > 0L } ?: localNow

    /**
     * 最新在前、最多 [limit] 条。
     *
     * DAO 里已经 `ORDER BY at DESC LIMIT`，这里是防线（也是单测能钉住的口径）；
     * `sortedByDescending` 对相等时刻是稳定排序，不会把同毫秒的两条顺序弄乱。
     *
     * **注意默认值是首页用的 [MAX_RECENT]**：历史页要全部记录，得显式传参或走 [groupByDay]。
     */
    fun newestFirst(events: List<MoodEvent>, limit: Int = MAX_RECENT): List<MoodEvent> =
        events.sortedByDescending { it.at }.take(limit)

    /**
     * 按**设备本地时区**的日历日分组，组内与组间都是最新在前；**不截断**。
     *
     * 几个刻意的口径：
     * 1. 用 `atStartOfDay(zone)` 而不是 `atTime(0, 0)`：有夏令时的时区里，某些日子根本没有
     *    00:00（时钟从 23:59 直接跳到 01:00），`atStartOfDay` 才是那天真正的起点。
     * 2. 时刻落在「未来」的（服务端时钟比本机快，或本机时区刚被改过）一律归**今天**：
     *    否则会出现「明天」这种组头。
     * 3. 分组只看日历日，不看时刻先后；排序另做，避免未来时刻把顺序带偏。
     */
    fun groupByDay(events: List<MoodEvent>, now: Long, zone: ZoneId): List<MoodDay> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return events
            .groupBy { event ->
                val date = Instant.ofEpochMilli(event.at).atZone(zone).toLocalDate()
                if (date.isAfter(today)) today else date
            }
            .map { (date, list) ->
                MoodDay(
                    dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli(),
                    label = when {
                        date == today -> MoodDayLabel.TODAY
                        date == today.minusDays(1) -> MoodDayLabel.YESTERDAY
                        date.year == today.year -> MoodDayLabel.SAME_YEAR
                        else -> MoodDayLabel.EARLIER_YEAR
                    },
                    date = date,
                    events = list.sortedByDescending { it.at },
                )
            }
            .sortedByDescending { it.dayStartMs }
    }
}
