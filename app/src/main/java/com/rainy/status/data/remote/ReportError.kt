package com.rainy.status.data.remote

/**
 * 上报结果分类。
 *
 * 分类的目的是**决定要不要重试**（见 `docs/api.md` 的「客户端重试约定」）：
 * 凭据失效、契约不符、payload 过大这三类重试一万次也没用，必须立刻停手并让用户知道；
 * 其余（限流、服务端故障、网络抖动）才值得退避重试。
 */
sealed interface ReportError {

    /** 缺 token / token 不符（401 / 403）。**不重试**，UI 提示重填 Token */
    data class Unauthorized(val httpCode: Int) : ReportError

    /** 契约不符（400 / 415）。**不重试**，是 App 侧 bug，写调试日志即可 */
    data class Contract(val httpCode: Int, val code: String?, val message: String?) : ReportError

    /** payload 过大（413）。**不重试** */
    data object PayloadTooLarge : ReportError

    /** 限流（429）。按 `Retry-After` 或指数退避重试 */
    data class RateLimited(val retryAfterSeconds: Long?) : ReportError

    /** 服务端故障（5xx）。退避重试 */
    data class Server(val httpCode: Int) : ReportError

    /** 连不上 / 超时 / DNS 失败。退避重试 */
    data class Network(val message: String) : ReportError

    /** 其他未分类（非 2xx 且不在上面枚举里）。按服务端故障对待，保守可重试 */
    data class Unknown(val httpCode: Int?, val message: String?) : ReportError

    /** 是否值得自动重试 */
    val retryable: Boolean
        get() = when (this) {
            is Unauthorized, is Contract, PayloadTooLarge -> false
            is RateLimited, is Server, is Network, is Unknown -> true
        }
}

/** 统一结果类型：成功带业务数据，失败带分类错误 */
sealed interface ApiResult<out T> {
    data class Success<T>(val value: T, val httpCode: Int) : ApiResult<T>
    data class Failure(val error: ReportError) : ApiResult<Nothing>
}