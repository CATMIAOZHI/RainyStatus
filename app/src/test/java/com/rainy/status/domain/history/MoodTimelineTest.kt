package com.rainy.status.domain.history

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 本机心情历史纯逻辑单测。
 *
 * 这里钉的是两个「不写测试就容易被将来改坏」的口径：
 * 1. **时刻取服务端还是本机**——混时间源会让列表顺序在改过设备时钟的机器上颠倒；
 * 2. **最新在前 + 截断条数**——DAO 里已有 `ORDER BY at DESC LIMIT`，但这里是第二道防线，
 *    顺序反了或条数没截，首页卡片会先是很难看出来、后来变得很难解释。
 */
class MoodTimelineTest {

    private fun event(at: Long, text: String = "t", emoji: String? = null) =
        MoodEvent(at = at, text = text, emoji = emoji)

    // ── 记哪个时刻 ──

    @Test
    fun `recordedAt prefers the server timestamp`() {
        // 服务端 1000 与本机 9999 差得离谱也要用服务端的：它同时也是云端 updated_at
        assertEquals(1000L, MoodTimeline.recordedAt(serverUpdatedAt = 1000L, localNow = 9999L))
    }

    @Test
    fun `recordedAt falls back to the local clock when the server value is missing`() {
        assertEquals(9999L, MoodTimeline.recordedAt(serverUpdatedAt = null, localNow = 9999L))
    }

    @Test
    fun `recordedAt falls back to the local clock when the server value is not usable`() {
        // 0 与负数都是「服务端没给」的等价表达，不能当成 1970 年写进主键
        assertEquals(9999L, MoodTimeline.recordedAt(serverUpdatedAt = 0L, localNow = 9999L))
        assertEquals(9999L, MoodTimeline.recordedAt(serverUpdatedAt = -1L, localNow = 9999L))
    }

    // ── 最新在前 ──

    @Test
    fun `newestFirst sorts by time descending`() {
        val events = listOf(event(100), event(300), event(200))
        assertEquals(listOf(300L, 200L, 100L), MoodTimeline.newestFirst(events).map { it.at })
    }

    @Test
    fun `newestFirst keeps insertion order for identical timestamps`() {
        // 同毫秒的两条（理论上只在快速连发时出现）：稳定排序，后来的不会跳到前面
        val events = listOf(event(100, text = "first"), event(100, text = "second"))
        assertEquals(
            listOf("first", "second"),
            MoodTimeline.newestFirst(events).map { it.text },
        )
    }

    @Test
    fun `newestFirst truncates to the limit`() {
        val events = (1L..10L).map { event(it) }
        assertEquals(
            listOf(10L, 9L, 8L),
            MoodTimeline.newestFirst(events).map { it.at },
        )
    }

    @Test
    fun `newestFirst defaults to MAX_RECENT`() {
        // 首页卡片条数由这个常量决定，改了它就是改首页高度
        assertEquals(3, MoodTimeline.MAX_RECENT)
        assertEquals(MoodTimeline.MAX_RECENT, MoodTimeline.newestFirst((1L..10L).map { event(it) }).size)
    }

    @Test
    fun `newestFirst handles an empty list`() {
        assertEquals(emptyList<MoodEvent>(), MoodTimeline.newestFirst(emptyList()))
    }
}
