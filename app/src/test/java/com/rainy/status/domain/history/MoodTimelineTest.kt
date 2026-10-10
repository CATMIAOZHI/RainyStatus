package com.rainy.status.domain.history

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 本机心情历史纯逻辑单测。
 *
 * 这里钉的是两个「不写测试就容易被将来改坏」的口径：
 * 1. **时刻取服务端还是本机**——混时间源会让列表顺序在改过设备时钟的机器上颠倒；
 * 2. **最新在前 + 截断条数**——DAO 里已有 `ORDER BY at DESC LIMIT`，但这里是第二道防线，
 *    顺序反了或条数没截，首页卡片会先是很难看出来、后来变得很难解释。
 */
class MoodTimelineTest {

    private fun event(at: Long, text: String = "t", emoji: String? = null) =
        MoodEvent(at = at, text = text, emoji = emoji)

    // ── 记哪个时刻 ──

    @Test
    fun `recordedAt prefers the server timestamp`() {
        // 服务端 1000 与本机 9999 差得离谱也要用服务端的：它同时也是云端 updated_at
        assertEquals(1000L, MoodTimeline.recordedAt(serverUpdatedAt = 1000L, localNow = 9999L))
    }

    @Test
    fun `recordedAt falls back to the local clock when the server value is missing`() {
        assertEquals(9999L, MoodTimeline.recordedAt(serverUpdatedAt = null, localNow = 9999L))
    }

    @Test
    fun `recordedAt falls back to the local clock when the server value is not usable`() {
        // 0 与负数都是「服务端没给」的等价表达，不能当成 1970 年写进主键
        assertEquals(9999L, MoodTimeline.recordedAt(serverUpdatedAt = 0L, localNow = 9999L))
        assertEquals(9999L, MoodTimeline.recordedAt(serverUpdatedAt = -1L, localNow = 9999L))
    }

    // ── 最新在前 ──

    @Test
    fun `newestFirst sorts by time descending`() {
        val events = listOf(event(100), event(300), event(200))
        assertEquals(listOf(300L, 200L, 100L), MoodTimeline.newestFirst(events).map { it.at })
    }

    @Test
    fun `newestFirst keeps insertion order for identical timestamps`() {
        // 同毫秒的两条（理论上只在快速连发时出现）：稳定排序，后来的不会跳到前面
        val events = listOf(event(100, text = "first"), event(100, text = "second"))
        assertEquals(
            listOf("first", "second"),
            MoodTimeline.newestFirst(events).map { it.text },
        )
    }

    @Test
    fun `newestFirst truncates to the limit`() {
        val events = (1L..10L).map { event(it) }
        assertEquals(
            listOf(10L, 9L, 8L),
            MoodTimeline.newestFirst(events).map { it.at },
        )
    }

    @Test
    fun `newestFirst defaults to MAX_RECENT`() {
        // 首页卡片条数由这个常量决定，改了它就是改首页高度
        assertEquals(3, MoodTimeline.MAX_RECENT)
        assertEquals(MoodTimeline.MAX_RECENT, MoodTimeline.newestFirst((1L..10L).map { event(it) }).size)
    }

    @Test
    fun `newestFirst handles an empty list`() {
        assertEquals(emptyList<MoodEvent>(), MoodTimeline.newestFirst(emptyList()))
    }

    // ── 按天分组（历史页用） ──

    /** 用固定偏移而不是 "Asia/Shanghai"：测试不依赖设备/CI 上的 tzdb，结论只由代码决定 */
    private val zone: ZoneId = ZoneId.of("+08:00")

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `groupByDay puts events of the same local day in one bucket`() {
        val now = at(2026, 10, 10, 12)
        val days = MoodTimeline.groupByDay(
            listOf(event(at(2026, 10, 10, 9)), event(at(2026, 10, 10, 8))),
            now,
            zone,
        )
        assertEquals(1, days.size)
        assertEquals(2, days.first().events.size)
    }

    @Test
    fun `groupByDay labels today yesterday same year and earlier year`() {
        val now = at(2026, 10, 10, 12)
        val days = MoodTimeline.groupByDay(
            listOf(
                event(at(2026, 10, 10, 9)),
                event(at(2026, 10, 9, 22)),
                event(at(2026, 10, 1, 9)),
                event(at(2025, 12, 31, 9)),
            ),
            now,
            zone,
        )
        assertEquals(
            // 组头四种文案，跨年那天必须单列——只写「12月31日」会和今年的 12 月 31 日撞车
            listOf(
                MoodDayLabel.TODAY,
                MoodDayLabel.YESTERDAY,
                MoodDayLabel.SAME_YEAR,
                MoodDayLabel.EARLIER_YEAR,
            ),
            days.map { it.label },
        )
    }

    @Test
    fun `groupByDay orders days newest first`() {
        val now = at(2026, 10, 10, 12)
        val days = MoodTimeline.groupByDay(
            // 刻意乱序传入：顺序不能依赖调用方（Room 的 Flow 顺序将来可能变）
            listOf(event(at(2026, 10, 1, 9)), event(at(2026, 10, 10, 9)), event(at(2026, 10, 9, 22))),
            now,
            zone,
        )
        assertEquals(
            listOf(at(2026, 10, 10, 0, 0), at(2026, 10, 9, 0, 0), at(2026, 10, 1, 0, 0)),
            days.map { it.dayStartMs },
        )
    }

    @Test
    fun `groupByDay orders events newest first inside a day`() {
        val now = at(2026, 10, 10, 23)
        val days = MoodTimeline.groupByDay(
            listOf(
                event(at(2026, 10, 10, 8), text = "早"),
                event(at(2026, 10, 10, 20), text = "晚"),
                event(at(2026, 10, 10, 14), text = "午"),
            ),
            now,
            zone,
        )
        assertEquals(listOf("晚", "午", "早"), days.single().events.map { it.text })
    }

    @Test
    fun `groupByDay does not truncate`() {
        // 历史页要的是全部记录：谁把 newestFirst 的默认 limit（= 3）带进来，这里就红
        val now = at(2026, 10, 10, 23)
        val events = (0 until 40).map { event(at(2026, 10, 10, 0) + it * 60_000L) }
        assertEquals(40, MoodTimeline.groupByDay(events, now, zone).single().events.size)
    }

    @Test
    fun `groupByDay treats a future timestamp as today`() {
        // 服务端时钟快几秒、或本机时区刚被改过：否则历史页会出现「10月11日」这种明天的组头
        val now = at(2026, 10, 10, 23, 59)
        val day = MoodTimeline.groupByDay(
            listOf(event(at(2026, 10, 11, 0, 30))),
            now,
            zone,
        ).single()
        assertEquals(MoodDayLabel.TODAY, day.label)
        assertEquals(at(2026, 10, 10, 0, 0), day.dayStartMs)
    }

    @Test
    fun `groupByDay splits by the given zone`() {
        // 同一个时刻，在 +08:00 是 10 日 01:00（今天），在 UTC 还是 9 日 17:00（昨天）。
        // 分组必须走设备本地时区，不能拿 UTC 日期糊过去
        val event = event(at(2026, 10, 10, 1))
        val now = at(2026, 10, 10, 12)
        assertEquals(
            MoodDayLabel.TODAY,
            MoodTimeline.groupByDay(listOf(event), now, zone).single().label,
        )
        assertEquals(
            MoodDayLabel.YESTERDAY,
            MoodTimeline.groupByDay(listOf(event), now, ZoneId.of("UTC")).single().label,
        )
    }

    @Test
    fun `groupByDay dayStartMs is midnight of that local day`() {
        val day = MoodTimeline.groupByDay(
            listOf(event(at(2026, 10, 9, 22))),
            at(2026, 10, 10, 12),
            zone,
        ).single()
        // 组头的日期文案与 dayStartMs 必须同源，否则「昨天」可能被标在某天 00:00 之外
        assertEquals(at(2026, 10, 9, 0, 0), day.dayStartMs)
        assertEquals(LocalDate.of(2026, 10, 9), day.date)
    }

    @Test
    fun `groupByDay handles an empty list`() {
        assertEquals(emptyList<MoodDay>(), MoodTimeline.groupByDay(emptyList(), at(2026, 10, 10), zone))
    }
}
