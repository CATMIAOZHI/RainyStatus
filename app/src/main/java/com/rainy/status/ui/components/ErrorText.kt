package com.rainy.status.ui.components

import com.rainy.status.R
import com.rainy.status.data.remote.ReportError

/**
 * 把 [ReportError] 映射成用户可读文案。
 *
 * 集中在一处的理由：首页、设置页的连接测试都要展示同一类错误，
 * 分开写必然出现「首页说 Token 被拒绝、设置页说未知错误」这种不一致。
 *
 * 两条硬规矩：
 * 1. **不把原始异常串丢给用户**（`Failed to connect to /10.0.2.2:443 …` 这种看不懂又吓人）。
 *    技术细节留在 [com.rainy.status.data.debug.DebugLog] 里给排查用，界面上只给可行动的信息。
 * 2. 401 的文案刻意指向「Token 与 AUTH_TOKEN 不一致」——这是用户唯一能修的原因。
 */
fun describeError(error: ReportError): UiText = when (error) {
    is ReportError.Unauthorized -> UiText.Resource(R.string.error_unauthorized)
    is ReportError.Contract -> UiText.Resource(R.string.error_bad_request, listOf(error.httpCode))
    ReportError.PayloadTooLarge -> UiText.Resource(R.string.error_too_large)
    is ReportError.RateLimited -> UiText.Resource(R.string.error_rate_limited)
    is ReportError.Server -> UiText.Resource(R.string.error_server, listOf(error.httpCode))
    is ReportError.Network -> {
        // 超时和「连不上」对用户是两件不同的事（前者重试可能就好，后者要先看网络），
        // 因此按异常文案分流。两种写法都要认：OkHttp 自己的超时是 "timeout"，
        // 而 JDK 侧 SocketTimeoutException 的文案是 "connect timed out" / "Read timed out"，
        // 只匹配 "timeout" 会把它们错判成「没有网络连接」。
        val message = error.message.lowercase()
        val timedOut = "timeout" in message || "timed out" in message
        if (timedOut) {
            UiText.Resource(R.string.error_timeout)
        } else {
            UiText.Resource(R.string.error_no_network)
        }
    }
    is ReportError.Unknown -> UiText.Resource(R.string.error_unknown)
}