package com.rainy.status.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 上报 DTO —— 字段名与云端白名单**逐一对应**（见 `docs/api.md`）。
 *
 * 云端 `cloud/src/lib/validate.ts` 对未知字段直接返回 400，所以这里**不许**加字段。
 * 新增字段的正确流程：改 `docs/api.md` → 改 `cloud/src/types.ts` 与 `validate.ts` → 再改这里。
 */
@Serializable
data class HeartbeatRequestDto(
    @SerialName("schemaVersion") val schemaVersion: Int = SCHEMA_VERSION,
    @SerialName("batteryPercent") val batteryPercent: Int? = null,
    @SerialName("charging") val charging: Boolean? = null,
    @SerialName("chargeSource") val chargeSource: String? = null,
    @SerialName("temperatureC") val temperatureC: Double? = null,
    @SerialName("network") val network: String? = null,
    @SerialName("deviceName") val deviceName: String? = null,
    @SerialName("appVersion") val appVersion: String? = null,
    @SerialName("clientTs") val clientTs: Long? = null,
    @SerialName("seq") val seq: Long? = null,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** `{ ok:true, receivedAt, nextExpectedInMs }` */
@Serializable
data class HeartbeatResponseDto(
    @SerialName("ok") val ok: Boolean = false,
    @SerialName("receivedAt") val receivedAt: Long? = null,
    @SerialName("nextExpectedInMs") val nextExpectedInMs: Long? = null,
)

/** 心情上报：`text` ≤ 140 码点，`emoji` ≤ 8 字符（云端校验） */
@Serializable
data class MoodRequestDto(
    @SerialName("schemaVersion") val schemaVersion: Int = HeartbeatRequestDto.SCHEMA_VERSION,
    @SerialName("text") val text: String,
    @SerialName("emoji") val emoji: String? = null,
)

/** `{ ok:true, updatedAt }` */
@Serializable
data class MoodResponseDto(
    @SerialName("ok") val ok: Boolean = false,
    @SerialName("updatedAt") val updatedAt: Long? = null,
)

/** 错误体：`{ ok:false, error:{ code, message } }` */
@Serializable
data class ApiErrorDto(
    @SerialName("ok") val ok: Boolean = false,
    @SerialName("error") val error: ApiErrorBody? = null,
)

@Serializable
data class ApiErrorBody(
    @SerialName("code") val code: String? = null,
    @SerialName("message") val message: String? = null,
)

/** `GET /api/health` —— 仅用于「连接测试」判定 Worker 是否可达 */
@Serializable
data class HealthResponseDto(
    @SerialName("ok") val ok: Boolean = false,
    @SerialName("status") val status: String? = null,
    @SerialName("service") val service: String? = null,
    @SerialName("time") val time: Long? = null,
)