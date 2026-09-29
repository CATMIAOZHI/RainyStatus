package com.rainy.status.domain.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 离线队列压缩单测：断网再久，恢复后也只发一条 */
class PendingQueueTest {

    private fun payload(
        battery: Int? = 50,
        charging: Boolean? = false,
        source: String? = "none",
        seq: Long = 1L,
    ) = HeartbeatPayload(
        batteryPercent = battery,
        charging = charging,
        chargeSource = source,
        temperatureC = null,
        network = null,
        deviceName = null,
        appVersion = "1.0.0",
        clientTs = 1_700_000_000_000L,
        seq = seq,
    )

    @Test
    fun `empty queue accepts incoming`() {
        val incoming = payload()
        assertSame(incoming, PendingQueue.merge(null, incoming))
    }

    @Test
    fun `insignificant difference keeps the old payload`() {
        // 电量没变、充电没翻转 → 丢弃新采样，保留旧的（避免无意义覆盖）
        val pending = payload(battery = 50, seq = 1)
        val incoming = payload(battery = 50, seq = 2)
        assertNull(PendingQueue.merge(pending, incoming))
    }

    @Test
    fun `one percent battery difference is significant`() {
        val pending = payload(battery = 50)
        val incoming = payload(battery = 51)
        assertSame(incoming, PendingQueue.merge(pending, incoming))
    }

    @Test
    fun `charging flip replaces the old payload`() {
        val pending = payload(battery = 50, charging = false, source = "none")
        val incoming = payload(battery = 50, charging = true, source = "ac")
        assertSame(incoming, PendingQueue.merge(pending, incoming))
    }

    @Test
    fun `charge source change while charging replaces the old payload`() {
        val pending = payload(charging = true, source = "usb")
        val incoming = payload(charging = true, source = "wireless")
        assertSame(incoming, PendingQueue.merge(pending, incoming))
    }

    @Test
    fun `source change while not charging is ignored`() {
        // 未充电时 chargeSource 恒为 none（见 HeartbeatPayloadFactory），
        // 这里防的是「有人手改 payload 造成假差异」
        val pending = payload(charging = false, source = "none")
        val incoming = payload(charging = false, source = "none")
        assertNull(PendingQueue.merge(pending, incoming))
    }

    @Test
    fun `field turned on counts as significant`() {
        // 用户刚打开电量上报开关：从 null → 有值，必须覆盖，否则云端永远看不到电量
        val pending = payload(battery = null)
        val incoming = payload(battery = 80)
        assertSame(incoming, PendingQueue.merge(pending, incoming))
    }

    @Test
    fun `field turned off counts as significant`() {
        val pending = payload(battery = 80)
        val incoming = payload(battery = null)
        assertSame(incoming, PendingQueue.merge(pending, incoming))
    }

    @Test
    fun `meaningful difference ignores seq and timestamp`() {
        // 只有 seq/clientTs 不同时不算差异，否则每条采样都会覆盖，压缩就失效了
        val a = payload(battery = 50, seq = 1)
        val b = payload(battery = 50, seq = 99)
        assertFalse(PendingQueue.isMeaningfullyDifferent(a, b))
    }

    @Test
    fun `both battery null is not a difference`() {
        val a = payload(battery = null)
        val b = payload(battery = null)
        assertFalse(PendingQueue.isMeaningfullyDifferent(a, b))
    }

    @Test
    fun `threshold matches constant`() {
        assertEquals(1, PendingQueue.MEANINGFUL_BATTERY_DELTA)
    }
}