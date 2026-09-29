package com.rainy.status.data.remote.dto

import com.rainy.status.data.remote.ApiJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HTTP 请求体的**上线形态**契约单测。
 *
 * 与 `domain/report/HeartbeatPayloadTest` 的分工：那边测 payload（离线队列落盘用的类型）
 * 的字段与取值，这边测**真的会被发出去的 JSON**——序列化配置（`ApiJson`）一旦漂移，
 * 字段就会在网络上悄悄消失，而 payload 层测试看不出来。
 *
 * 依据：`docs/api.md` 的请求示例 + `cloud/src/lib/validate.ts` 的白名单。
 */
class DtosContractTest {

    /** 与主代码同一份配置；不是测试里自建的 `Json { }` */
    private val json = ApiJson.create()

    private fun encodeHeartbeat(dto: HeartbeatRequestDto): JsonObject =
        json.encodeToJsonElement(HeartbeatRequestDto.serializer(), dto).jsonObject

    private val heartbeatDto = HeartbeatRequestDto(
        schemaVersion = HeartbeatRequestDto.SCHEMA_VERSION,
        batteryPercent = 87,
        charging = true,
        chargeSource = "ac",
        temperatureC = 31.5,
        network = "wifi",
        deviceName = "WaterRainCat-Phone",
        appVersion = "1.0.0",
        clientTs = 1_759_211_879_500L,
        seq = 12345L,
    )

    @Test
    fun `heartbeat body keys match the cloud whitelist`() {
        // 与 validate.ts 的 HEARTBEAT_KEYS 逐字一致（多一个键就是 400）
        val obj = encodeHeartbeat(heartbeatDto)
        assertEquals(
            setOf(
                "schemaVersion",
                "batteryPercent",
                "charging",
                "chargeSource",
                "temperatureC",
                "network",
                "deviceName",
                "appVersion",
                "clientTs",
                "seq",
            ),
            obj.keys,
        )
    }

    @Test
    fun `schemaVersion is actually sent on the wire`() {
        // 它有默认值，若 encodeDefaults 关了就会被省略，与 docs/api.md 的示例不符
        val obj = encodeHeartbeat(heartbeatDto)
        assertEquals("1", obj.getValue("schemaVersion").toString())
    }

    @Test
    fun `disabled fields are explicit null on the wire`() {
        // 用户关掉的项发显式 null 而不是「整个键消失」：抓包时能区分
        // 「这一项被关了」与「这一项忘了发」，云端对两者判定相同
        val minimal = HeartbeatRequestDto(
            batteryPercent = null,
            charging = null,
            chargeSource = null,
            temperatureC = null,
            network = null,
            deviceName = null,
        )
        val obj = encodeHeartbeat(minimal)
        for (key in listOf(
            "batteryPercent", "charging", "chargeSource",
            "temperatureC", "network", "deviceName",
        )) {
            assertTrue("$key must exist as a key", key in obj.keys)
            assertEquals(JsonNull, obj.getValue(key))
        }
    }

    @Test
    fun `mood body keys match the cloud whitelist`() {
        val dto = MoodRequestDto(text = "困了喵…", emoji = "😴")
        val obj = json.encodeToJsonElement(MoodRequestDto.serializer(), dto).jsonObject
        assertEquals(setOf("schemaVersion", "text", "emoji"), obj.keys)
        assertEquals("1", obj.getValue("schemaVersion").toString())
    }

    @Test
    fun `response parsing tolerates unknown fields`() {
        // 云端将来加字段时旧 App 必须还能解析，否则一次服务端升级会让旧版本全挂
        val raw = """{"ok":true,"receivedAt":1,"nextExpectedInMs":2,"futureField":"x"}"""
        val parsed = json.decodeFromString(HeartbeatResponseDto.serializer(), raw)
        assertTrue(parsed.ok)
        assertEquals(2L, parsed.nextExpectedInMs)
    }

    @Test
    fun `explicit null responses fall back to defaults`() {
        // coerceInputValues：显式 null 落到带默认值的非空属性上取默认值而不是抛异常
        val nulls = """{"ok":null,"status":null,"service":null,"time":null}"""
        val health = json.decodeFromString(HealthResponseDto.serializer(), nulls)
        assertEquals(false, health.ok)
    }
}