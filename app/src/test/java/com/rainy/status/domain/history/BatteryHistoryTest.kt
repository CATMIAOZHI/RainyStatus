package com.rainy.status.domain.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 本地电量历史纯逻辑单测。
 *
 * 重点覆盖三类最容易写错的地方：
 * 1. **窗口口径**（闭区间、按本地日切）；
 * 2. **充电次数**（未知状态怎么算、跨窗口/跨日不能重复计）；
 * 3. **该不该记**（值没变且间隔太短的重复点要跳掉；历史本身永久保留，不做裁剪）。
 */
class BatteryHistoryTest {

    /** 固定 +08:00 时区：没有夏令时，边界时刻算起来不会自我怀疑 */
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(day: String, hour: Int, minute: Int = 0): Long =
        LocalDateTime.parse("${day}T%02d:%02d:00".format(hour, minute))
            .atZone(zone).toInstant().toEpochMilli()

    private val now = at("2026-10-10", 12)

    private fun sample(t: Long, b: Int? = 50, c: Boolean? = false) = BatterySample(t = t, b = b, c = c)

    // ── 该不该记（去重） ──

    @Test
    fun `shouldRecord accepts the first sample of an empty database`() {
        assertTrue(BatteryHistory.shouldRecord(null, sample(now)))
    }

    @Test
    fun `shouldRecord skips an unchanged value inside the dedupe window`() {
        val last = sample(now - 60_000, b = 50, c = false)
        assertFalse(BatteryHistory.shouldRecord(last, sample(now, b = 50, c = false)))
    }

    @Test
    fun `shouldRecord records an unchanged value once the gap reaches the dedupe window`() {
        val last = sample(now - BatteryHistory.SAME_VALUE_MIN_GAP_MS, b = 50, c = false)
        assertTrue(BatteryHistory.shouldRecord(last, sample(now, b = 50, c = false)))
    }

    @Test
    fun `shouldRecord records when only the charging state changed`() {
        val last = sample(now - 60_000, b = 50, c = false)
        assertTrue(BatteryHistory.shouldRecord(last, sample(now, b = 50, c = true)))
    }

    @Test
    fun `shouldRecord records when only the battery level changed`() {
        val last = sample(now - 60_000, b = 50, c = false)
        assertTrue(BatteryHistory.shouldRecord(last, sample(now, b = 51, c = false)))
    }

    @Test
    fun `shouldRecord treats an unknown reading as a change`() {
        // null 与具体值不是同一件事：读不到 → 读到 50% 也要记，
        // 否则「不知道」和「真的 50%」会被压成一条直线
        assertTrue(BatteryHistory.shouldRecord(sample(now - 60_000, b = null), sample(now, b = 50)))
        assertTrue(BatteryHistory.shouldRecord(sample(now - 60_000, b = 50), sample(now, b = null)))
    }

    // ── 加载窗口 ──

    @Test
    fun `load window covers a full week plus lead-in and slack`() {
        // 7 个本地日 + 1 天前置（给 chargingStateBefore 看窗口外那条）+ 1 天余量 = 9 天
        assertEquals(9L * 24 * 60 * 60 * 1000, BatteryHistory.LOAD_WINDOW_MS)
        assertTrue(BatteryHistory.LOAD_WINDOW_MS > BatteryHistory.H24_MS * BatteryHistory.DAYS_IN_WEEK)
    }

    // ── 窗口 ──

    @Test
    fun `inWindow includes both bounds`() {
        val from = now - 1000
        val samples = listOf(
            sample(from - 1),
            sample(from),
            sample(now),
            sample(now + 1),
        )
        assertEquals(listOf(from, now), BatteryHistory.inWindow(samples, from, now).map { it.t })
    }

    // ── 充电次数 ──

    @Test
    fun `chargeSessions counts unknown to charging as a session`() {
        val sessions = BatteryHistory.chargeSessions(
            listOf(sample(0, c = null), sample(600_000, c = true))
        )
        assertEquals(1, sessions)
    }

    @Test
    fun `chargeSessions does not count a session already running before the window`() {
        val sessions = BatteryHistory.chargeSessions(
            listOf(sample(0, c = true), sample(600_000, c = true), sample(1_200_000, c = false)),
            prevCharging = true
        )
        assertEquals(0, sessions)
    }

    @Test
    fun `chargeSessions skips unknown samples without losing the previous state`() {
        // 「充电中 → 未知 → 充电中」：云端 skip 未知、保留上一次状态，因此只算 1 次
        val sessions = BatteryHistory.chargeSessions(
            listOf(sample(0, c = true), sample(600_000, c = null), sample(1_200_000, c = true))
        )
        assertEquals(1, sessions)
    }

    @Test
    fun `chargeSessions counts each restart of charging after a known pause`() {
        // 上一窗口/上一天结束时没在充电 → 窗口里的两次 true 各算一次
        val sessions = BatteryHistory.chargeSessions(
            listOf(sample(0, c = true), sample(600_000, c = false), sample(1_200_000, c = true)),
            prevCharging = false
        )
        assertEquals(2, sessions)
    }

    @Test
    fun `chargingStateBefore looks back without a limit`() {
        val samples = listOf(
            sample(now - 3 * 24 * 60 * 60 * 1000L, c = true),
            // 中间断档三天，仍是「最后一次知道的状态」
        )
        assertEquals(true, BatteryHistory.chargingStateBefore(samples, now))
    }

    @Test
    fun `chargingStateBefore ignores samples without a known state`() {
        val samples = listOf(sample(now - 1000, c = null))
        assertNull(BatteryHistory.chargingStateBefore(samples, now))
    }

    // ── 充电竖带 ──

    @Test
    fun `chargeBands merges consecutive charging samples`() {
        val bands = BatteryHistory.chargeBands(
            listOf(
                sample(0, c = false),
                sample(10, c = true),
                sample(20, c = true),
                sample(30, c = false),
            )
        )
        assertEquals(listOf(10L..30L), bands)
    }

    @Test
    fun `chargeBands keeps a single point band`() {
        val bands = BatteryHistory.chargeBands(
            listOf(sample(0, c = false), sample(10, c = true), sample(20, c = false))
        )
        assertEquals(listOf(10L..20L), bands)
    }

    // ── 断档分段 ──

    @Test
    fun `segments splits the line when the gap exceeds the threshold`() {
        val gap = BatteryHistory.LINE_GAP_MS
        val samples = listOf(
            sample(0), sample(gap), sample(gap * 2),
            sample(gap * 2 + gap + 1), sample(gap * 2 + gap * 2),
        )
        val segments = BatteryHistory.segments(samples, gap)
        assertEquals(listOf(3, 2), segments.map { it.size })
    }

    // ── 每日汇总 ──

    @Test
    fun `dailyRanges returns seven local days ending with today`() {
        val days = BatteryHistory.dailyRanges(emptyList(), now, zone)

        assertEquals(BatteryHistory.DAYS_IN_WEEK, days.size)
        assertEquals(BatteryHistory.dayStartMs(LocalDate.of(2026, 10, 10), zone), days.last().dayStartMs)
        assertEquals(BatteryHistory.dayStartMs(LocalDate.of(2026, 10, 4), zone), days.first().dayStartMs)
        assertTrue(days.all { !it.hasData })
    }

    @Test
    fun `dailyRanges buckets samples by local day`() {
        val samples = listOf(
            sample(at("2026-10-09", 9), b = 80),
            sample(at("2026-10-09", 20), b = 40),
            sample(at("2026-10-10", 8), b = 60),
            // 前一天 23:59 与后一天 00:00 必须落在各自的桶里
            sample(at("2026-10-08", 23, 59), b = 99),
        )
        val days = BatteryHistory.dailyRanges(samples, now, zone).associateBy { it.dayStartMs }

        val day9 = days.getValue(BatteryHistory.dayStartMs(LocalDate.of(2026, 10, 9), zone))
        assertEquals(40, day9.min)
        assertEquals(80, day9.max)

        val day10 = days.getValue(BatteryHistory.dayStartMs(LocalDate.of(2026, 10, 10), zone))
        assertEquals(60, day10.min)
        assertEquals(60, day10.max)

        val day8 = days.getValue(BatteryHistory.dayStartMs(LocalDate.of(2026, 10, 8), zone))
        assertEquals(99, day8.max)

        val day7 = days.getValue(BatteryHistory.dayStartMs(LocalDate.of(2026, 10, 7), zone))
        assertNull(day7.min)
        assertNull(day7.max)
    }

    @Test
    fun `dailyRanges counts a charge session crossing midnight only once`() {
        val samples = listOf(
            sample(at("2026-10-09", 23, 50), c = true),
            sample(at("2026-10-10", 0, 10), c = true),
            sample(at("2026-10-10", 0, 40), c = false),
        )
        val days = BatteryHistory.dailyRanges(samples, now, zone).associateBy { it.dayStartMs }

        assertEquals(
            1,
            days.getValue(BatteryHistory.dayStartMs(LocalDate.of(2026, 10, 9), zone)).chargeSessions
        )
        // 跨午夜仍在充电：后一天不能再算一次（与云端日层同一口径）
        assertEquals(
            0,
            days.getValue(BatteryHistory.dayStartMs(LocalDate.of(2026, 10, 10), zone)).chargeSessions
        )
    }

    @Test
    fun `dayStartMs respects the given zone`() {
        val start = BatteryHistory.dayStartMs(LocalDate.of(2026, 10, 10), zone)
        // +08:00 的 00:00 就是前一天的 16:00Z
        assertEquals(LocalDateTime.parse("2026-10-09T16:00:00").atZone(ZoneId.of("UTC")).toInstant().toEpochMilli(), start)
    }

    // ── 最低/最高 ──

    @Test
    fun `minMax ignores samples without a battery reading`() {
        assertNull(BatteryHistory.minMax(listOf(sample(0, b = null), sample(1, b = null))))

        val minMax = BatteryHistory.minMax(listOf(sample(0, b = 80), sample(1, b = null), sample(2, b = 20)))
        assertEquals(20 to 80, minMax)
    }
}