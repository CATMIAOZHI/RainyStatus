package com.rainy.status.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 错误分类的**重试语义**单测。
 *
 * 这张表决定「App 遇到这种错误该不该退避重试」，填错的后果是具体的：
 * - 把 404 当成可重试 → 地址填错时 App 会无限退避重试，且用户只看到「未知错误」，
 *   根本不知道要改的是地址（审计发现的 P1-2 就是这个）。
 * - 把 401 当成可重试 → Token 填错后一直重试，永远好不了。
 */
class ReportErrorTest {

    @Test
    fun `credential and contract failures are not retryable`() {
        assertFalse(ReportError.Unauthorized(401).retryable)
        assertFalse(ReportError.Contract(400, "invalid_payload", "bad").retryable)
        assertFalse(ReportError.PayloadTooLarge.retryable)
    }

    @Test
    fun `404 is not retryable because the address is the problem`() {
        // 路径是代码里写死的 /api/heartbeat，404 只可能来自 base URL 不对
        assertFalse(ReportError.EndpointNotFound(null).retryable)
        assertFalse(ReportError.EndpointNotFound("Not found").retryable)
    }

    @Test
    fun `transient failures are retryable`() {
        assertTrue(ReportError.RateLimited(30).retryable)
        assertTrue(ReportError.Server(503).retryable)
        assertTrue(ReportError.Network("timeout").retryable)
        assertTrue(ReportError.Unknown(418, "teapot").retryable)
    }

    @Test
    fun `429 carries the server retry-after hint`() {
        val limited = ReportError.RateLimited(45)
        assertEquals(45L, limited.retryAfterSeconds)
    }
}
