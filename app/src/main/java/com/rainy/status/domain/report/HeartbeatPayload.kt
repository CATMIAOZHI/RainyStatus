package com.rainy.status.domain.report

import com.rainy.status.domain.model.DeviceSnapshot
import com.rainy.status.domain.model.FieldOptions
import kotlinx.serialization.Serializable

/**
 * 上报 payload（与云端契约一一对应，见 `docs/api.md`）。
 *
 * 所有字段名必须与 `cloud/src/lib/validate.ts` 的 `HEARTBEAT_KEYS` 白名单完全一致——
 * **多一个字段就会被 400 拒绝**（这是刻意的防漂移护栏）。改动此文件必须同步：
 * `docs/api.md`、`cloud/src/types.ts`、`cloud/src/lib/validate.ts`。
 *
 * `null` 语义：用户关掉的字段不发送「假值」，而是发 `null`，云端渲染为不显示。
 *
 * 标 `@Serializable` 是为了让离线队列能直接落盘（DataStore 存 JSON），
 * 这样队列压缩逻辑（PendingQueue）不必在两套类型之间来回转换。
 */
@Serializable
data class HeartbeatPayload(
    val batteryPercent: Int?,
    val charging: Boolean?,
    val chargeSource: String?,
    val temperatureC: Double?,
    val network: String?,
    val deviceName: String?,
    val appVersion: String,
    val clientTs: Long,
    val seq: Long,
)

/**
 * 把本地采样 + 字段开关裁成 payload。
 *
 * 注意电量与充电的**联动**：`charging == false` 时 `chargeSource` 必须为 `none`，
 * 否则网页会显示「未充电 · 电源适配器」这种自相矛盾的内容。
 */
object HeartbeatPayloadFactory {

    fun build(
        snapshot: DeviceSnapshot,
        fields: FieldOptions,
        appVersion: String,
        seq: Long,
    ): HeartbeatPayload {
        val includeCharging = fields.includeCharging
        return HeartbeatPayload(
            batteryPercent = if (fields.includeBattery) snapshot.batteryPercent else null,
            charging = if (includeCharging) snapshot.charging else null,
            chargeSource = when {
                !includeCharging -> null
                snapshot.charging -> snapshot.chargeSource
                else -> DeviceSnapshot.SOURCE_NONE
            },
            temperatureC = if (fields.includeTemperature) snapshot.temperatureC else null,
            network = if (fields.includeNetwork) snapshot.network else null,
            deviceName = fields.deviceName.trim().takeIf { it.isNotEmpty() },
            appVersion = appVersion,
            clientTs = snapshot.capturedAt,
            seq = seq,
        )
    }
}