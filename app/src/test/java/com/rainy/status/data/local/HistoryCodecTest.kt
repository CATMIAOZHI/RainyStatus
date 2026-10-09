package com.rainy.status.data.local

import com.rainy.status.domain.history.BatterySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 本地历史存储编解码单测：格式必须紧凑，且坏数据不能把界面带崩 */
class HistoryCodecTest {

    @Test
    fun `round trip preserves every field`() {
        val samples = listOf(
            BatterySample(t = 1, b = 42, c = true),
            BatterySample(t = 2, b = null, c = false),
            BatterySample(t = 3, b = 7, c = null),
        )
        assertEquals(samples, HistoryCodec.decode(HistoryCodec.encode(samples)))
    }

    @Test
    fun `encode omits null fields but keeps false`() {
        // 空值不写进 JSON：2048 个点的 blob 体积直接省三成
        assertEquals("[{\"t\":1}]", HistoryCodec.encode(listOf(BatterySample(t = 1))))
        // false 不是默认值，必须写出来，否则「没在充电」会被读成「不知道」
        assertTrue(HistoryCodec.encode(listOf(BatterySample(t = 1, c = false))).contains("\"c\":false"))
    }

    @Test
    fun `decode tolerates missing and unknown keys`() {
        assertEquals(
            listOf(BatterySample(t = 5, b = 60, c = null)),
            HistoryCodec.decode("[{\"t\":5,\"b\":60,\"futureField\":\"x\"}]")
        )
    }

    @Test
    fun `decode returns empty list for missing or broken payloads`() {
        assertEquals(emptyList<BatterySample>(), HistoryCodec.decode(null))
        assertEquals(emptyList<BatterySample>(), HistoryCodec.decode(""))
        assertEquals(emptyList<BatterySample>(), HistoryCodec.decode("not json at all"))
        // 形状不对（对象而不是数组）也不能抛异常
        assertEquals(emptyList<BatterySample>(), HistoryCodec.decode("{}"))
    }

    @Test
    fun `encode of an empty list stays decodable`() {
        val encoded = HistoryCodec.encode(emptyList())
        assertFalse(encoded.isBlank())
        assertEquals(emptyList<BatterySample>(), HistoryCodec.decode(encoded))
    }
}