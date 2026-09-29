package com.rainy.status.data.remote

import com.rainy.status.data.remote.dto.ApiErrorDto
import com.rainy.status.data.remote.dto.HealthResponseDto
import com.rainy.status.data.remote.dto.HeartbeatRequestDto
import com.rainy.status.data.remote.dto.HeartbeatResponseDto
import com.rainy.status.data.remote.dto.MoodRequestDto
import com.rainy.status.data.remote.dto.MoodResponseDto
import com.rainy.status.domain.server.EndpointNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 上报用的 HTTP 客户端。直接用 OkHttp + kotlinx-serialization，
 * **不引入 Retrofit**（只有 3 个端点，Retrofit 只会增加间接层与依赖）。
 *
 * 所有方法都是挂起函数并在 [Dispatchers.IO] 执行；调用方（Repository）负责退避与重试。
 */
class StatusApi(
    private val client: OkHttpClient,
    private val json: Json,
) {

    /** 单次请求超时：比心跳间隔短得多，避免卡住整条上报链 */
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * `POST /api/heartbeat`
     *
     * 成功条件：HTTP 2xx **且** body 的 `ok == true`。
     * 只判 HTTP 码不够——Cloudflare 在额度耗尽等情况下可能返回 200 + 错误体。
     */
    suspend fun sendHeartbeat(
        baseUrl: String,
        token: String,
        payload: HeartbeatRequestDto,
    ): ApiResult<HeartbeatResponseDto> = request(
        baseUrl = baseUrl,
        path = PATH_HEARTBEAT,
        token = token,
        body = json.encodeToString(HeartbeatRequestDto.serializer(), payload),
        parse = { body -> json.decodeFromString(HeartbeatResponseDto.serializer(), body) },
    )

    /** `POST /api/mood` */
    suspend fun sendMood(
        baseUrl: String,
        token: String,
        payload: MoodRequestDto,
    ): ApiResult<MoodResponseDto> = request(
        baseUrl = baseUrl,
        path = PATH_MOOD,
        token = token,
        body = json.encodeToString(MoodRequestDto.serializer(), payload),
        parse = { body -> json.decodeFromString(MoodResponseDto.serializer(), body) },
    )

    /**
     * `GET /api/health` —— 连接测试用。
     *
     * 用它而不是发一条真心跳：health 不碰 KV，测试连接不该产生任何写。
     * 但 health 无鉴权，**无法验证 Token**；设置页的「连接测试」因此
     * 用 health 判可达、再按需要单独校验 Token（见 StatusRepository.testConnection）。
     */
    suspend fun health(baseUrl: String): ApiResult<HealthResponseDto> = withContext(Dispatchers.IO) {
        val url = EndpointNormalizer.apiUrl(baseUrl, PATH_HEALTH)

        try {
            // Request 构造必须在 try 内：地址里若藏着非法端口，`Request.Builder().url()`
            // 会抛 IllegalArgumentException（不是 IOException），放在外面就会逃逸成崩溃
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext ApiResult.Failure(classifyHttp(response.code, null, body))
                }
                val parsed = runCatching {
                    json.decodeFromString(HealthResponseDto.serializer(), body)
                }.getOrNull()
                    ?: return@withContext ApiResult.Failure(
                        ReportError.Contract(response.code, null, "Malformed health response")
                    )
                ApiResult.Success(parsed, response.code)
            }
        } catch (e: IOException) {
            ApiResult.Failure(ReportError.Network(e.message ?: e::class.java.simpleName))
        } catch (e: IllegalArgumentException) {
            // 与 request() 同理：非法 URL 抛的是 IAE。端口越界已被 EndpointNormalizer 拦住，
            // 但它的 host 规则比 OkHttp 的 HttpUrl 宽松（例如带下划线的域名），
            // 漏进这里就会从 SettingsViewModel 的 viewModelScope 逃逸成崩溃。
            ApiResult.Failure(ReportError.Network(e.message ?: "Invalid URL"))
        }
    }

    private suspend fun <T> request(
        baseUrl: String,
        path: String,
        token: String,
        body: String,
        parse: (String) -> T,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        val url = EndpointNormalizer.apiUrl(baseUrl, path)

        try {
            // 同 health()：Request 构造必须落在 try 内，非法 URL 抛的是
            // IllegalArgumentException 而不是 IOException，放在 try 外会一路逃逸到 UI 线程
            val request = Request.Builder()
                .post(body.toRequestBody(jsonMediaType))
                .url(url)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $token")
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                val code = response.code

                if (!response.isSuccessful) {
                    return@withContext ApiResult.Failure(
                        classifyHttp(code, response.header("Retry-After"), responseBody)
                    )
                }

                val parsed = runCatching { parse(responseBody) }.getOrNull()
                    ?: return@withContext ApiResult.Failure(
                        ReportError.Contract(code, null, "Malformed response body")
                    )

                // body 里的 ok=false 也要当失败（HTTP 200 + 业务错误的防御）
                if (!isOk(parsed)) {
                    return@withContext ApiResult.Failure(
                        ReportError.Unknown(code, "Server returned ok=false")
                    )
                }
                ApiResult.Success(parsed, code)
            }
        } catch (e: IOException) {
            ApiResult.Failure(ReportError.Network(e.message ?: e::class.java.simpleName))
        } catch (e: IllegalArgumentException) {
            // URL 非法（例如端口越界）。刻意只捕这两个具体类型，不用 Exception：
            // 宽捕会把协程取消异常一起吞掉，导致「请求永不返回」这种更难查的故障。
            ApiResult.Failure(ReportError.Network(e.message ?: "Invalid URL"))
        }
    }

    /** 反射式读 `ok` 字段，避免为每个 DTO 写一遍接口实现 */
    private fun isOk(value: Any): Boolean = when (value) {
        is HeartbeatResponseDto -> value.ok
        is MoodResponseDto -> value.ok
        is HealthResponseDto -> value.ok
        else -> true
    }

    private fun classifyHttp(code: Int, retryAfter: String?, body: String): ReportError {
        val errorCode: String?
        val errorMessage: String?
        val parsed = runCatching { json.decodeFromString(ApiErrorDto.serializer(), body) }.getOrNull()
        errorCode = parsed?.error?.code
        errorMessage = parsed?.error?.message

        return when (code) {
            401, 403 -> ReportError.Unauthorized(code)
            400, 415 -> ReportError.Contract(code, errorCode, errorMessage)
            404 -> ReportError.EndpointNotFound(errorMessage)
            413 -> ReportError.PayloadTooLarge
            429 -> ReportError.RateLimited(retryAfter?.trim()?.toLongOrNull())
            in 500..599 -> ReportError.Server(code)
            else -> ReportError.Unknown(code, errorMessage ?: errorCode)
        }
    }

    companion object {
        const val PATH_HEARTBEAT = "/api/heartbeat"
        const val PATH_MOOD = "/api/mood"
        const val PATH_HEALTH = "/api/health"

        /** 连接 10 秒 / 读 15 秒：上报是小请求，长超时没有意义只会拖慢重试 */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}