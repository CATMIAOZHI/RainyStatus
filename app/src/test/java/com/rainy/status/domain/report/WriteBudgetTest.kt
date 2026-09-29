package com.rainy.status.domain.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 每日写预算单测（额度按 UTC 日重置，与 Cloudflare 一致） */
class WriteBudgetTest {

    /** 2023-11-14T22:13:20Z，UTC 日起点为当天 00:00（epoch 1699920000000） */
    private val now = 1_700_000_000_000L
    private val dayMs = 86_400_000L

    @Test
    fun `utc day start aligns to midnight`() {
        val start = WriteBudget.utcDayStart(now)
        assertEquals(0L, start % dayMs)
        assertTrue(start <= now && start + dayMs > now)
    }

    @Test
    fun `utc day start is timezone independent`() {
        // 纯取模实现：同一时刻在任何设备时区下都必须算出同一个值
        assertEquals(
            WriteBudget.utcDayStart(now),
            now - Math.floorMod(now, dayMs)
        )
    }

    @Test
    fun `writes below soft limit keep configured interval`() {
        assertEquals(600_000L, WriteBudget.effectiveIntervalMs(600_000L, 0))
        assertEquals(600_000L, WriteBudget.effectiveIntervalMs(600_000L, WriteBudget.SOFT_LIMIT - 1))
    }

    @Test
    fun `writes at soft limit are throttled to 15 minutes`() {
        assertEquals(
            WriteBudget.THROTTLED_INTERVAL_MS,
            WriteBudget.effectiveIntervalMs(600_000L, WriteBudget.SOFT_LIMIT)
        )
    }

    @Test
    fun `throttling never shortens a longer configured interval`() {
        // 用户本来设了 30 分钟，降频不能把它「提速」成 15 分钟
        val long = 30 * 60 * 1000L
        assertEquals(long, WriteBudget.effectiveIntervalMs(long, WriteBudget.SOFT_LIMIT))
    }

    @Test
    fun `is_throttled flag matches threshold`() {
        assertFalse(WriteBudget.isThrottled(WriteBudget.SOFT_LIMIT - 1))
        assertTrue(WriteBudget.isThrottled(WriteBudget.SOFT_LIMIT))
    }

    @Test
    fun `normalize keeps count inside the same utc day`() {
        assertEquals(42, WriteBudget.normalizeWrites(42, WriteBudget.utcDayStart(now), now))
    }

    @Test
    fun `normalize resets count on a new utc day`() {
        // 昨天的计数不能算进今天（否则刚重置额度就被误判为已降频）
        val yesterday = WriteBudget.utcDayStart(now) - dayMs
        assertEquals(0, WriteBudget.normalizeWrites(999, yesterday, now))
    }

    @Test
    fun `normalize resets when never recorded`() {
        assertEquals(0, WriteBudget.normalizeWrites(500, 0L, now))
    }

    @Test
    fun `soft limit leaves headroom under free tier`() {
        // Cloudflare 免费写额度 1000/天；600 的软上限要给心情与重试留余量
        assertTrue(WriteBudget.SOFT_LIMIT < 1000)
        assertTrue(WriteBudget.SOFT_LIMIT >= 400)
    }
}