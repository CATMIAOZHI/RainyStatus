package com.rainy.status.domain.report

/**
 * 离线待发队列 —— 刻意压成「最多一条」。
 *
 * 断网 8 小时会积压 48 条心跳，全部补发会把 KV 写额度瞬间打爆，
 * 而且补发的旧电量毫无意义（云端只存「最新快照」）。因此：
 *
 * - 已有待发时，**无意义差异**（电量差 < 1%、充电状态未翻转）→ 丢弃新采样，保留旧的
 * - **有意义差异** → 用新采样**替换**旧的（旧电量已经过时，发出去只会污染云端）
 *
 * 结果：断网 8 小时恢复后只发 1 条（最新），而不是 48 条。
 * 云端曲线有段空缺是有意为之——那段本来就是掉线状态。
 */
object PendingQueue {

    /** 电量差异小于此值视为「无意义」 */
    const val MEANINGFUL_BATTERY_DELTA = 1

    /**
     * 入队决策。
     *
     * @param pending 现有待发（null = 空队列）
     * @param incoming 新采样
     * @return 新的待发内容，或 null 表示「保留旧的 / 无需入队」
     */
    fun merge(pending: HeartbeatPayload?, incoming: HeartbeatPayload): HeartbeatPayload? {
        if (pending == null) return incoming
        return if (isMeaningfullyDifferent(pending, incoming)) incoming else null
    }

    /**
     * 两条 payload 是否存在「值得覆盖旧数据」的差异。
     *
     * 只比较用户能感知的字段：电量、充电状态。`seq` / `clientTs` / 温度之类的
     * 微差不算意义——否则每次采样都会覆盖，队列压缩就白做了。
     */
    fun isMeaningfullyDifferent(old: HeartbeatPayload, new: HeartbeatPayload): Boolean {
        val oldBattery = old.batteryPercent
        val newBattery = new.batteryPercent
        if (oldBattery != null && newBattery != null) {
            if (kotlin.math.abs(newBattery - oldBattery) >= MEANINGFUL_BATTERY_DELTA) return true
        } else if (oldBattery != newBattery) {
            // 一边有、一边没有（用户刚改了上报开关）→ 值得覆盖
            return true
        }

        if (old.charging != new.charging) return true
        // 充电方式翻转（换个充电器）也算
        if (old.charging == true && new.charging == true && old.chargeSource != new.chargeSource) return true

        return false
    }
}