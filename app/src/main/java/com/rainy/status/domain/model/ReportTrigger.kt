package com.rainy.status.domain.model

/**
 * 上报触发源。
 *
 * 三通道互补（见 `docs/design.md` 第 9.3 节）：
 * - 亮屏：前台服务内协程循环 → [PERIODIC]
 * - 息屏 / Doze：`AlarmManager` 精确闹钟 → [PERIODIC]
 * - 事件即时：充电插拔 [CHARGING]、开机 [BOOT]、网络恢复 [NETWORK]、解锁 [UNLOCK]、电量变化 [BATTERY_CHANGE]
 *
 * [MANUAL] 是用户在首页点「立即上报」，绕过一切节流（但仍受字段/配置校验约束）。
 */
enum class ReportTrigger {
    PERIODIC,
    MANUAL,
    CHARGING,
    BOOT,
    NETWORK,
    UNLOCK,
    BATTERY_CHANGE,
}
