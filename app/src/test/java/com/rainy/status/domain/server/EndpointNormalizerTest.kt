package com.rainy.status.domain.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上报地址规范化单测。
 *
 * 用户会以各种形式粘贴地址（尾斜杠、带路径、漏 scheme、多余空格），
 * 这里要保证「能修的自动修，不能用的明确拒绝」——
 * 拒绝的理由要具体（[EndpointNormalizer.Result]），因为设置页要据此给出提示。
 */
class EndpointNormalizerTest {

    private fun valid(raw: String): String {
        val result = EndpointNormalizer.normalize(raw)
        assertTrue("expected valid for '$raw', got $result", result is EndpointNormalizer.Result.Valid)
        return (result as EndpointNormalizer.Result.Valid).baseUrl
    }

    @Test
    fun `plain host gets https scheme`() {
        assertEquals("https://example.workers.dev", valid("example.workers.dev"))
    }

    @Test
    fun `existing https is preserved`() {
        assertEquals("https://status.example.com", valid("https://status.example.com"))
    }

    @Test
    fun `trailing slash is trimmed`() {
        // 不 Trim 的话拼出来就是 //api/heartbeat，Worker 路由匹配不到
        assertEquals("https://status.example.com", valid("https://status.example.com/"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("https://status.example.com", valid("  https://status.example.com  "))
    }

    @Test
    fun `base path is preserved without trailing slash`() {
        assertEquals("https://example.com/rainystatus", valid("https://example.com/rainystatus/"))
    }

    @Test
    fun `port is preserved`() {
        assertEquals("https://localhost:8787", valid("localhost:8787"))
    }

    @Test
    fun `uppercase scheme is accepted`() {
        assertEquals("https://example.com", valid("HTTPS://example.com"))
    }

    // ── 拒绝 ──

    @Test
    fun `empty input is rejected as empty`() {
        assertEquals(EndpointNormalizer.Result.Empty, EndpointNormalizer.normalize(""))
        assertEquals(EndpointNormalizer.Result.Empty, EndpointNormalizer.normalize("   "))
    }

    @Test
    fun `http is rejected`() {
        // network_security_config 禁明文，http 地址在真机上根本连不上，早拒绝早提示
        assertEquals(
            EndpointNormalizer.Result.NotHttps,
            EndpointNormalizer.normalize("http://example.com")
        )
    }

    @Test
    fun `other schemes are rejected`() {
        assertEquals(
            EndpointNormalizer.Result.NotHttps,
            EndpointNormalizer.normalize("ftp://example.com")
        )
    }

    @Test
    fun `host without a dot is rejected`() {
        // 打错主机名（比如把 workers.dev 漏了）比连不通更难排查，直接拒绝
        assertEquals(
            EndpointNormalizer.Result.Malformed,
            EndpointNormalizer.normalize("https://notadomain")
        )
    }

    @Test
    fun `localhost is allowed for local dev`() {
        assertEquals("https://localhost", valid("https://localhost"))
        assertEquals("https://127.0.0.1:8787", valid("https://127.0.0.1:8787"))
    }

    @Test
    fun `user info in url is rejected`() {
        // https://user:pass@host 这种形式容易被钓鱼地址滥用，且没有任何正当用途
        assertEquals(
            EndpointNormalizer.Result.Malformed,
            EndpointNormalizer.normalize("https://user:pass@example.com")
        )
    }

    @Test
    fun `out of range port is rejected`() {
        // URI 会接受 99999 这样的越界端口（照样解析出 host/port），但 OkHttp 的 HttpUrl
        // 会因此返回 null，构造 Request 时抛 IllegalArgumentException。
        // 若这里放行，用户只要填个 99999 就能让「连接测试」把 App 打崩。
        assertEquals(
            EndpointNormalizer.Result.Malformed,
            EndpointNormalizer.normalize("https://example.com:99999")
        )
        assertEquals(
            EndpointNormalizer.Result.Malformed,
            EndpointNormalizer.normalize("https://example.com:70000")
        )
    }

    @Test
    fun `upper bound port is accepted`() {
        assertEquals("https://example.com:65535", valid("https://example.com:65535"))
    }

    // ── 拼接 ──

    @Test
    fun `api url joins without double slash`() {
        assertEquals(
            "https://example.com/api/heartbeat",
            EndpointNormalizer.apiUrl("https://example.com", "/api/heartbeat")
        )
    }

    @Test
    fun `api url tolerates base without slash and path without slash`() {
        assertEquals(
            "https://example.com/api/mood",
            EndpointNormalizer.apiUrl("https://example.com", "api/mood")
        )
    }

    @Test
    fun `api url preserves base path`() {
        assertEquals(
            "https://example.com/base/api/health",
            EndpointNormalizer.apiUrl("https://example.com/base", "/api/health")
        )
    }

    @Test
    fun `api url tolerates trailing slash in base`() {
        assertEquals(
            "https://example.com/api/status",
            EndpointNormalizer.apiUrl("https://example.com/", "/api/status")
        )
    }
}