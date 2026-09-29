package com.rainy.status.domain.server

/**
 * 上报地址规范化。
 *
 * 用户会以各种形式粘贴地址：带尾斜杠、带路径、漏了 scheme、多打了空格。
 * 这里统一成 `https://host[:port][/base]`（无尾斜杠），后续拼 `/api/xxx`。
 *
 * 刻意**只允许 https**：
 * - `network_security_config.xml` 已禁明文（cleartextTrafficPermitted=false）
 * - 用户自己的域名/workers.dev 都是 https，没有正当的 http 场景
 *
 * 用 URI 手工解析而非 `java.net.URL`：URL 会做 DNS 无关但很啰嗦的规范化，
 * 且对非法输入抛异常的行为不稳定；这里要的是「可预期的接受/拒绝」。
 */
object EndpointNormalizer {

    /** 解析失败原因，供设置页给出具体提示 */
    sealed interface Result {
        data class Valid(val baseUrl: String) : Result
        data object Empty : Result
        data object NotHttps : Result
        data object Malformed : Result
    }

    /**
     * @param raw 用户原始输入
     * @return [Result.Valid.baseUrl] 形如 `https://example.com` 或 `https://example.com/base`
     */
    fun normalize(raw: String): Result {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Result.Empty

        // 用户常常只写主机名；补 https 是唯一安全的假设
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"

        val uri = try {
            java.net.URI(withScheme)
        } catch (_: Exception) {
            return Result.Malformed
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "https") return Result.NotHttps

        val host = uri.host
        if (host.isNullOrBlank() || !host.contains('.')) {
            // 没有点的主机名（除 localhost）几乎一定是用户打错了；
            // workers.dev 这类一定带点。localhost 用于本地 wrangler dev 调试。
            if (host != "localhost" && host != "127.0.0.1") return Result.Malformed
        }

        if (uri.userInfo != null) return Result.Malformed

        // 端口必须显式校验范围：`java.net.URI` 对 99999 这种越界端口**不报错**（照样给出
        // host 与 port），于是地址会被判为合法并落盘；而 OkHttp 的 HttpUrl 会因此返回 null，
        // 构造 Request 时抛 IllegalArgumentException。若在 try 之外构造请求，就会变成
        // 「设置页点一下连接测试 → 崩溃」。这里直接拒绝，用户当场得到提示。
        if (uri.port > 65535) return Result.Malformed
        val port = if (uri.port in 1..65535) ":${uri.port}" else ""
        val path = uri.path?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: ""

        return Result.Valid("https://$host$port$path")
    }

    /** 拼接 API 路径；[baseUrl] 必须已经过 [normalize] */
    fun apiUrl(baseUrl: String, path: String): String {
        val normalizedBase = baseUrl.trimEnd('/')
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        return normalizedBase + normalizedPath
    }
}