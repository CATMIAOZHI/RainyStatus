package com.rainy.status.domain.model

/**
 * 一次设备状态采样（本地读取，尚未裁剪成上报字段）。
 *
 * 与云端契约的对应关系见 `docs/api.md`：
 * - [chargeSource] ∈ `ac` / `usb` / `wireless` / `none`
 * - [network] ∈ `wifi` / `cellular` / `ethernet` / `none` / `unknown`
 */
data class DeviceSnapshot(
    /**
     * 电量百分比；**读不到时为 null**。
     *
     * 为什么不是 `Int`：真实环境里读不到电量（无电池信息的模拟器、极早期开机）与
     * 「真的 0%」是两件事。用 0 兜底会让通知栏显示「电量 0%」，也会让 [ReportGate]
     * 的电量差值判定拿到假基准。宁可让类型表达「不知道」。
     */
    val batteryPercent: Int?,
    val charging: Boolean,
    val chargeSource: String,
    /**
     * 电池温度；**读不到时为 null**（与 [batteryPercent] 同一套语义）。
     *
     * 不用 0.0 兜底：`EXTRA_TEMPERATURE` 缺失时给 0.0 会把「不知道」变成一个看起来
     * 正常的假读数发到网页上，而 0 °C 恰好不是任何真实手机的温度。
     */
    val temperatureC: Double?,
    val network: String,
    val capturedAt: Long,
) {
    companion object {
        const val SOURCE_AC = "ac"
        const val SOURCE_USB = "usb"
        const val SOURCE_WIRELESS = "wireless"
        const val SOURCE_NONE = "none"

        const val NET_WIFI = "wifi"
        const val NET_CELLULAR = "cellular"
        const val NET_ETHERNET = "ethernet"
        const val NET_NONE = "none"
        const val NET_UNKNOWN = "unknown"
    }
}

/**
 * 上报字段开关（用户设置）。
 *
 * 注意：关掉某个字段**不是不发请求**，而是把该字段上报为 `null`——
 * 这样云端会把它渲染成「不显示」，而不是显示一个假的 0。
 * 电量与充电状态同时关闭时，网页只剩「人还在不在」这条信息，这也是合法用法。
 */
data class FieldOptions(
    val includeBattery: Boolean,
    val includeCharging: Boolean,
    val includeTemperature: Boolean,
    val includeNetwork: Boolean,
    val deviceName: String,
)
