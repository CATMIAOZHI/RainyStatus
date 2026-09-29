package com.rainy.status.domain.report

import com.rainy.status.domain.model.ReportTrigger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 门控判定单测。
 *
 * 这是整个 App 里最容易出错也最贵的一段逻辑（判错就会打爆 KV 写额度，
 * 或反过来让心跳静默丢失），因此覆盖得比其他类更细。
 */
class ReportGateTest {

    private val now = 1_700_000_000_000L
    private val interval = 600_000L

    private fun input(
        trigger: ReportTrigger,
        lastSuccessAt: Long = now - interval,
        lastAttemptAt: Long = now - interval,
        lastBatteryPercent: Int? = 50,
        currentBatteryPercent: Int? = 50,
        lastBatteryChangeAt: Long = now - 600_000L,
        hasPending: Boolean = false,
    ) = GateInput(
        trigger = trigger,
        now = now,
        lastSuccessAt = lastSuccessAt,
        lastAttemptAt = lastAttemptAt,
        lastBatteryPercent = lastBatteryPercent,
        currentBatteryPercent = currentBatteryPercent,
        lastBatteryChangeAt = lastBatteryChangeAt,
        hasPending = hasPending,
        effectiveIntervalMs = interval,
    )

    // ── 无条件放行 ──

    @Test
    fun `manual always reports`() {
        assertTrue(
            ReportGate.shouldReport(
                input(ReportTrigger.MANUAL, lastSuccessAt = now, lastAttemptAt = now)
            )
        )
    }

    @Test
    fun `charging always reports`() {
        assertTrue(
            ReportGate.shouldReport(
                input(ReportTrigger.CHARGING, lastSuccessAt = now, lastAttemptAt = now)
            )
        )
    }

    @Test
    fun `boot always reports even without prior success`() {
        assertTrue(
            ReportGate.shouldReport(
                input(ReportTrigger.BOOT, lastSuccessAt = 0L, lastAttemptAt = 0L)
            )
        )
    }

    // ── 周期上报 ──

    @Test
    fun `periodic blocked when last attempt too recent`() {
        // 距上次成功很久，但距上次尝试只有 10 秒 → 仍在最短间隔内
        assertFalse(
            ReportGate.shouldReport(
                input(
                    ReportTrigger.PERIODIC,
                    lastSuccessAt = now - interval * 10,
                    lastAttemptAt = now - 10_000L,
                )
            )
        )
    }

    @Test
    fun `periodic allowed at 60 percent of interval`() {
        // 宽容系数 0.6：距上次成功达到 60% 间隔即可放行，让先到的那条通道胜出
        assertTrue(
            ReportGate.shouldReport(
                input(
                    ReportTrigger.PERIODIC,
                    lastSuccessAt = now - (interval * 0.6).toLong(),
                    lastAttemptAt = now - ReportGate.MIN_ATTEMPT_GAP_MS,
                )
            )
        )
    }

    @Test
    fun `periodic blocked just below 60 percent`() {
        assertFalse(
            ReportGate.shouldReport(
                input(
                    ReportTrigger.PERIODIC,
                    lastSuccessAt = now - (interval * 0.6).toLong() + 1,
                    lastAttemptAt = now - ReportGate.MIN_ATTEMPT_GAP_MS,
                )
            )
        )
    }

    @Test
    fun `periodic reports on first ever run`() {
        // lastSuccessAt = 0 表示「从未成功」，应视为可以立刻上报，而不是等一个间隔
        assertTrue(
            ReportGate.shouldReport(
                input(ReportTrigger.PERIODIC, lastSuccessAt = 0L, lastAttemptAt = 0L)
            )
        )
    }

    // ── 解锁 / 网络 ──

    @Test
    fun `unlock requires full interval`() {
        assertFalse(
            ReportGate.shouldReport(
                input(ReportTrigger.UNLOCK, lastSuccessAt = now - (interval * 0.9).toLong())
            )
        )
        assertTrue(
            ReportGate.shouldReport(
                input(ReportTrigger.UNLOCK, lastSuccessAt = now - interval)
            )
        )
    }

    @Test
    fun `network with pending bypasses interval`() {
        assertTrue(
            ReportGate.shouldReport(
                input(ReportTrigger.NETWORK, lastSuccessAt = now, hasPending = true)
            )
        )
    }

    @Test
    fun `network without pending requires full interval`() {
        assertFalse(
            ReportGate.shouldReport(
                input(ReportTrigger.NETWORK, lastSuccessAt = now - 60_000L, hasPending = false)
            )
        )
    }

    // ── 电量变化 ──

    @Test
    fun `battery change blocked below delta threshold`() {
        assertFalse(
            ReportGate.shouldReport(
                input(
                    ReportTrigger.BATTERY_CHANGE,
                    lastBatteryPercent = 50,
                    currentBatteryPercent = 52, // 2% < 3%
                )
            )
        )
    }

    @Test
    fun `battery change allowed at delta threshold`() {
        assertTrue(
            ReportGate.shouldReport(
                input(
                    ReportTrigger.BATTERY_CHANGE,
                    lastBatteryPercent = 50,
                    currentBatteryPercent = 53,
                    lastBatteryChangeAt = now - ReportGate.MIN_BATTERY_DELTA_GAP_MS,
                )
            )
        )
    }

    @Test
    fun `battery change blocked inside debounce window`() {
        // 差值够了，但距上次电量变化只过了 30 秒 → 防抖挡下
        assertFalse(
            ReportGate.shouldReport(
                input(
                    ReportTrigger.BATTERY_CHANGE,
                    lastBatteryPercent = 50,
                    currentBatteryPercent = 60,
                    lastBatteryChangeAt = now - 30_000L,
                )
            )
        )
    }

    @Test
    fun `battery change with no baseline reports`() {
        // 首次运行没有基准电量，不能拿 null 当 0 算差值（会得到 50% 的假变化）
        assertTrue(
            ReportGate.shouldReport(
                input(
                    ReportTrigger.BATTERY_CHANGE,
                    lastBatteryPercent = null,
                    currentBatteryPercent = 80,
                    lastBatteryChangeAt = now,
                )
            )
        )
    }

    @Test
    fun `battery drop also counts as change`() {
        // 方向不看符号：从 80 掉到 70 同样值得上报
        assertTrue(
            ReportGate.shouldReport(
                input(
                    ReportTrigger.BATTERY_CHANGE,
                    lastBatteryPercent = 80,
                    currentBatteryPercent = 70,
                    lastBatteryChangeAt = now - ReportGate.MIN_BATTERY_DELTA_GAP_MS,
                )
            )
        )
    }

    @Test
    fun `battery change with unreadable level does not report`() {
        // 读不到电量（null）时没有「变化」可言：既不该上报一条没有电量的心跳，
        // 也不该拿 null 当基准去比较
        assertFalse(
            ReportGate.shouldReport(
                input(
                    ReportTrigger.BATTERY_CHANGE,
                    lastBatteryPercent = 50,
                    currentBatteryPercent = null,
                    lastBatteryChangeAt = now,
                )
            )
        )
    }

    // ── 时钟回拨（用户手动改系统时间）──

    @Test
    fun `clock going backwards does not crash and stays blocked`() {
        // lastSuccessAt 在未来 → elapsed 为负，必须夹到 0 而不是当成已经等够了
        assertFalse(
            ReportGate.shouldReport(
                input(ReportTrigger.PERIODIC, lastSuccessAt = now + 3_600_000L, lastAttemptAt = now + 3_600_000L)
            )
        )
    }
}