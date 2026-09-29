package com.rainy.status.domain.report

import com.rainy.status.domain.model.ReportTrigger

/**
 * 上报门控输入。全部是「已经读好的状态」，因此本类与判定函数都可以纯单测。
 */
data class GateInput(
    val trigger: ReportTrigger,
    val now: Long,
    /** 上次**成功**上报时刻；从未成功为 0 */
    val lastSuccessAt: Long,
    /** 上次**尝试**（含失败）时刻；从未尝试为 0 */
    val lastAttemptAt: Long,
    /** 上次用于比较的电量；无记录为 null */
    val lastBatteryPercent: Int?,
    /** 本次读到的电量；读不到为 null */
    val currentBatteryPercent: Int?,
    /** 上次电量**发生变化**的时刻；无记录为 0 */
    val lastBatteryChangeAt: Long,
    /** 是否有待发队列（离线积压） */
    val hasPending: Boolean,
    /** 当前生效的间隔（可能因写预算被拉长） */
    val effectiveIntervalMs: Long,
)

/**
 * 上报门控：决定某个触发源此刻「该不该真的发一次」。
 *
 * 为什么需要它：
 * 1. 前台服务协程与精确闹钟是**两条并行通道**，同一时刻都会想发，必须去重；
 * 2. 电量变化是高频广播，每变 1% 就发会把 KV 写额度打爆；
 * 3. 失败重试不能变成风暴（撞 KV 同 key 写频限 1 次/秒 → 429）。
 *
 * 判定规则（与 `docs/design.md` 第 9.4 节一致）：
 *
 * | 触发 | 规则 |
 * |---|---|
 * | MANUAL | 永远放行（用户显式动作） |
 * | CHARGING | 永远放行（低频、语义重要） |
 * | BOOT | 永远放行（重启后需要立刻恢复在线状态） |
 * | PERIODIC | 距上次成功 ≥ 60% 间隔，且距上次尝试 ≥ [MIN_ATTEMPT_GAP_MS] |
 * | UNLOCK | 距上次成功 ≥ 间隔 |
 * | NETWORK | 有积压立即冲；否则同 UNLOCK |
 * | BATTERY_CHANGE | 变化 ≥ [MIN_BATTERY_DELTA]% 且距上次变化 ≥ [MIN_BATTERY_DELTA_GAP_MS] |
 */
object ReportGate {

    /** 电量百分比最小变化量：低于此值不发（只更新本地状态） */
    const val MIN_BATTERY_DELTA = 3

    /** 电量变化的两次上报最短间隔（防抖） */
    const val MIN_BATTERY_DELTA_GAP_MS = 120_000L

    /**
     * 任意两次自动上报之间的最短间隔。
     *
     * 远大于 KV 的同 key 写频限（1 次/秒），留足安全边际；
     * 之所以不用 1 秒这种下限：10 分钟心跳下，1 秒内的重复发送一定是逻辑错误而非需求。
     */
    const val MIN_ATTEMPT_GAP_MS = 30_000L

    /**
     * 周期上报的宽容系数：距上次成功达到 `间隔 × 0.6` 即可放行。
     *
     * 两条通道（协程 / 闹钟）的相位不可能完全对齐，取 0.6 让先到者胜、
     * 后到者被门控挡掉，从而在「不丢心跳」和「不重复写」之间取得平衡。
     */
    const val PERIODIC_TOLERANCE = 0.6

    fun shouldReport(input: GateInput): Boolean = when (input.trigger) {
        ReportTrigger.MANUAL,
        ReportTrigger.CHARGING,
        ReportTrigger.BOOT -> true

        ReportTrigger.PERIODIC ->
            elapsedSince(input.lastAttemptAt, input.now) >= MIN_ATTEMPT_GAP_MS &&
                elapsedSince(input.lastSuccessAt, input.now) >=
                (input.effectiveIntervalMs * PERIODIC_TOLERANCE).toLong()

        // 进程被系统杀掉后自动拉起：不能无条件放行（会被反复杀的后台环境当成免费写额度），
        // 按 UNLOCK 规则——距上次成功够一个间隔才发
        ReportTrigger.UNLOCK,
        ReportTrigger.STICKY_RESTART ->
            elapsedSince(input.lastSuccessAt, input.now) >= input.effectiveIntervalMs

        ReportTrigger.NETWORK ->
            input.hasPending || elapsedSince(input.lastSuccessAt, input.now) >= input.effectiveIntervalMs

        ReportTrigger.BATTERY_CHANGE -> batteryDeltaWorthReporting(input)
    }

    private fun batteryDeltaWorthReporting(input: GateInput): Boolean {
        // 先看本次读数：读不到电量就没有「变化」可言，也不该为一条 null 电量去写 KV
        val current = input.currentBatteryPercent ?: return false
        val previous = input.lastBatteryPercent ?: return true
        if (kotlin.math.abs(current - previous) < MIN_BATTERY_DELTA) return false
        return elapsedSince(input.lastBatteryChangeAt, input.now) >= MIN_BATTERY_DELTA_GAP_MS
    }

    /** `0` 视为「从未发生」，避免开机时 `now - 0` 变成天文数字 */
    private fun elapsedSince(then: Long, now: Long): Long =
        if (then <= 0L) Long.MAX_VALUE else (now - then).coerceAtLeast(0L)
}
