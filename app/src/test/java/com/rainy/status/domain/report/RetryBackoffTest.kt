package com.rainy.status.domain.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 退避策略单测（用固定种子的 Random 让抖动可复现） */
class RetryBackoffTest {

    private val fixed = Random(42)

    @Test
    fun `initial delay is 30 seconds plus jitter`() {
        val delay = RetryBackoff.delayMs(1, fixed)
        assertInJitterRange(RetryBackoff.INITIAL_MS, delay)
    }

    @Test
    fun `delay doubles each attempt`() {
        // 只验「大致翻倍」：抖动是 ±20%，不会跨过 2 倍
        val first = RetryBackoff.delayMs(1, fixed)
        val second = RetryBackoff.delayMs(2, fixed)
        assertTrue("expected doubling, got $first → $second", second > first)
    }

    @Test
    fun `delay is capped at 15 minutes`() {
        // attempts 很大时用位移而非 pow：不会溢出成 Infinity，也不超过上限
        val delay = RetryBackoff.delayMs(60, fixed)
        assertInJitterRange(RetryBackoff.MAX_MS, delay)
    }

    @Test
    fun `delay never below one second`() {
        for (attempts in 1..40) {
            assertTrue(RetryBackoff.delayMs(attempts, Random(attempts)) >= 1_000L)
        }
    }

    @Test
    fun `zero attempts falls back to initial`() {
        assertEquals(RetryBackoff.INITIAL_MS, RetryBackoff.delayMs(0, fixed))
    }

    // ── 429 的 Retry-After ──

    @Test
    fun `retry after is honored inside the window`() {
        val delay = RetryBackoff.delayFor429(120, attempts = 1, random = fixed)
        assertEquals(120_000L, delay)
    }

    @Test
    fun `retry after is clamped to the max`() {
        // 服务端可能给一个极大值，不能照抄（否则一次限流就让心跳停几小时）
        val delay = RetryBackoff.delayFor429(86_400, attempts = 1, random = fixed)
        assertEquals(RetryBackoff.MAX_MS, delay)
    }

    @Test
    fun `retry after is clamped to the min`() {
        // 1 秒是 KV 同 key 写频限的安全下限
        val delay = RetryBackoff.delayFor429(1, attempts = 1, random = fixed)
        assertEquals(RetryBackoff.INITIAL_MS, delay)
    }

    @Test
    fun `missing retry after falls back to exponential`() {
        val delay = RetryBackoff.delayFor429(null, attempts = 1, random = fixed)
        assertInJitterRange(RetryBackoff.INITIAL_MS, delay)
        val zero = RetryBackoff.delayFor429(0, attempts = 1, random = fixed)
        assertInJitterRange(RetryBackoff.INITIAL_MS, zero)
    }

    private fun assertInJitterRange(base: Long, actual: Long) {
        val delta = (base * RetryBackoff.JITTER_RATIO).toLong()
        assertTrue(
            "expected $actual within $base ± $delta",
            actual in (base - delta)..(base + delta)
        )
    }
}