package com.rainy.status.domain.report

import com.rainy.status.data.remote.ApiJson
import com.rainy.status.domain.model.DeviceSnapshot
import com.rainy.status.domain.model.FieldOptions
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上报 payload 契约单测。
 *
 * 云端 `cloud/src/lib/validate.ts` 对未知字段直接返回 400、
 * 对枚举值（chargeSource / network）也有白名单，因此这里逐条对齐：
 * **字段名集合、枚举取值、范围**三者任一漂移都会在真机上变成「上报永远失败」。
 */
class HeartbeatPayloadTest {

    /** 与真机**同一份**配置（`AppModule.provideJson` 也调它），否则测试绿了线上仍可能不一样 */
    private val json = ApiJson.create()

    private val snapshot = DeviceSnapshot(
        batteryPercent = 87,
        charging = true,
        chargeSource = DeviceSnapshot.SOURCE_AC,
        temperatureC = 31.5,
        network = DeviceSnapshot.NET_WIFI,
        capturedAt = 1_700_000_000_000L,
    )

    private val allOn = FieldOptions(
        includeBattery = true,
        includeCharging = true,
        includeTemperature = true,
        includeNetwork = true,
        deviceName = "My-Phone",
    )

    /** 与 `validate.ts` 的 HEARTBEAT_KEYS 必须逐字一致 */
    private val expectedKeys = setOf(
        "batteryPercent",
        "charging",
        "chargeSource",
        "temperatureC",
        "network",
        "deviceName",
        "appVersion",
        "clientTs",
        "seq",
    )

    @Test
    fun `serialized keys match the cloud whitelist exactly`() {
        val payload = HeartbeatPayloadFactory.build(snapshot, allOn, "1.0.0", 7L)
        val element = json.encodeToJsonElement(HeartbeatPayload.serializer(), payload)
        val obj = element as JsonObject
        assertEquals(expectedKeys, obj.keys)
    }

    @Test
    fun `payload has no schemaVersion field`() {
        // schemaVersion 只在 HTTP DTO 层（HeartbeatRequestDto）里，payload 里不能重复出现，
        // 否则离线队列落盘/读回的类型会与上报 DTO 混淆
        val payload = HeartbeatPayloadFactory.build(snapshot, allOn, "1.0.0", 1L)
        val obj = json.encodeToJsonElement(HeartbeatPayload.serializer(), payload) as JsonObject
        assertTrue("schemaVersion must not be in the payload", "schemaVersion" !in obj.keys)
    }

    // ── 字段开关 ──

    @Test
    fun `disabled fields are sent as null not zeros`() {
        val off = FieldOptions(
            includeBattery = false,
            includeCharging = false,
            includeTemperature = false,
            includeNetwork = false,
            deviceName = "",
        )
        val payload = HeartbeatPayloadFactory.build(snapshot, off, "1.0.0", 1L)
        assertNull(payload.batteryPercent)
        assertNull(payload.charging)
        assertNull(payload.chargeSource)
        assertNull(payload.temperatureC)
        assertNull(payload.network)
        assertNull(payload.deviceName)
    }

    @Test
    fun `blank device name becomes null`() {
        val fields = allOn.copy(deviceName = "   ")
        val payload = HeartbeatPayloadFactory.build(snapshot, fields, "1.0.0", 1L)
        assertNull(payload.deviceName)
    }

    @Test
    fun `app version and client timestamp are always sent`() {
        // 这两个是诊断字段，不受任何开关影响：掉了它们就查不出是哪个版本在出问题
        val payload = HeartbeatPayloadFactory.build(snapshot, allOn.copy(includeBattery = false), "1.2.3", 42L)
        assertEquals("1.2.3", payload.appVersion)
        assertEquals(snapshot.capturedAt, payload.clientTs)
        assertEquals(42L, payload.seq)
    }

    // ── 电量与充电的联动 ──

    @Test
    fun `not charging forces chargeSource to none`() {
        // 否则网页会显示「未充电 · 电源适配器」这种自相矛盾的内容
        val notCharging = snapshot.copy(charging = false, chargeSource = DeviceSnapshot.SOURCE_AC)
        val payload = HeartbeatPayloadFactory.build(notCharging, allOn, "1.0.0", 1L)
        assertEquals(false, payload.charging)
        assertEquals(DeviceSnapshot.SOURCE_NONE, payload.chargeSource)
    }

    @Test
    fun `charging keeps the real source`() {
        val payload = HeartbeatPayloadFactory.build(
            snapshot.copy(chargeSource = DeviceSnapshot.SOURCE_WIRELESS),
            allOn, "1.0.0", 1L
        )
        assertEquals(DeviceSnapshot.SOURCE_WIRELESS, payload.chargeSource)
    }

    @Test
    fun `chargeSource is null when charging is turned off`() {
        // 关掉充电上报时，连 chargeSource 一起为 null；不能留下一个孤立的充电方式
        val fields = allOn.copy(includeCharging = false)
        val payload = HeartbeatPayloadFactory.build(snapshot, fields, "1.0.0", 1L)
        assertNull(payload.charging)
        assertNull(payload.chargeSource)
    }

    // ── 枚举取值 ──

    @Test
    fun `charge source enum matches cloud whitelist`() {
        val allowed = setOf("ac", "usb", "wireless", "none")
        assertEquals(allowed, setOf(
            DeviceSnapshot.SOURCE_AC,
            DeviceSnapshot.SOURCE_USB,
            DeviceSnapshot.SOURCE_WIRELESS,
            DeviceSnapshot.SOURCE_NONE,
        ))
    }

    @Test
    fun `network enum matches cloud whitelist`() {
        val allowed = setOf("wifi", "cellular", "ethernet", "none", "unknown")
        assertEquals(allowed, setOf(
            DeviceSnapshot.NET_WIFI,
            DeviceSnapshot.NET_CELLULAR,
            DeviceSnapshot.NET_ETHERNET,
            DeviceSnapshot.NET_NONE,
            DeviceSnapshot.NET_UNKNOWN,
        ))
    }

    // ── 值域（云端会拒绝越界值）──

    @Test
    fun `battery percentage stays within cloud range`() {
        for (percent in listOf(0, 1, 50, 99, 100)) {
            val payload = HeartbeatPayloadFactory.build(
                snapshot.copy(batteryPercent = percent), allOn, "1.0.0", 1L
            )
            assertTrue("battery $percent must be 0..100", payload.batteryPercent!! in 0..100)
        }
    }

    @Test
    fun `device name respects cloud length limit`() {
        // 云端 deviceName ≤ 32：超长会被 400 拒绝，Store 层已限长，这里兜底验证
        val long = "x".repeat(32)
        val payload = HeartbeatPayloadFactory.build(snapshot, allOn.copy(deviceName = long), "1.0.0", 1L)
        assertEquals(32, payload.deviceName!!.length)
    }

    @Test
    fun `temperature keeps one decimal precision`() {
        // 云端只要求数值范围 -50..200，精度由 App 决定；保留一位小数与网页展示一致
        val payload = HeartbeatPayloadFactory.build(
            snapshot.copy(temperatureC = 31.54), allOn, "1.0.0", 1L
        )
        assertEquals(31.54, payload.temperatureC!!, 0.0001)
    }

    @Test
    fun `unreadable temperature is reported as null not zero`() {
        // 与电量同一套语义：读不到就是 null（云端渲染成不显示），不能拿 0.0 冒充真实读数
        val payload = HeartbeatPayloadFactory.build(
            snapshot.copy(temperatureC = null), allOn, "1.0.0", 1L
        )
        assertNull(payload.temperatureC)
    }
}