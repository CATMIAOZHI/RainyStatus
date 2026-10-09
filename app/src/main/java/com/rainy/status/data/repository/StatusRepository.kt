package com.rainy.status.data.repository

import com.rainy.status.data.debug.DebugLog
import com.rainy.status.data.device.DeviceStateReader
import com.rainy.status.data.local.AppSettings
import com.rainy.status.data.local.HistoryStore
import com.rainy.status.data.local.RuntimeState
import com.rainy.status.data.local.RuntimeStateStore
import com.rainy.status.data.local.SettingsStore
import com.rainy.status.data.remote.ApiResult
import com.rainy.status.data.remote.ReportError
import com.rainy.status.data.remote.StatusApi
import com.rainy.status.data.remote.dto.HeartbeatRequestDto
import com.rainy.status.data.remote.dto.MoodRequestDto
import com.rainy.status.domain.history.BatterySample
import com.rainy.status.domain.model.DeviceSnapshot
import com.rainy.status.domain.model.FieldOptions
import com.rainy.status.domain.model.ReportTrigger
import com.rainy.status.domain.report.GateInput
import com.rainy.status.domain.report.HeartbeatPayload
import com.rainy.status.domain.report.HeartbeatPayloadFactory
import com.rainy.status.domain.report.PendingQueue
import com.rainy.status.domain.report.ReportGate
import com.rainy.status.domain.report.RetryBackoff
import com.rainy.status.domain.report.WriteBudget
import com.rainy.status.domain.server.EndpointNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** 上报结果，供 UI 与通知展示 */
sealed interface ReportOutcome {
    data class Success(val receivedAt: Long?) : ReportOutcome
    data class Skipped(val reason: String) : ReportOutcome
    data class Failed(val error: ReportError) : ReportOutcome
    /** 未配置地址/Token */
    data object NotConfigured : ReportOutcome
}

/** 连接测试结果 */
sealed interface ConnectionTestResult {
    data class Ok(val httpCode: Int) : ConnectionTestResult
    data class Unauthorized(val httpCode: Int) : ConnectionTestResult
    data class Failed(val error: ReportError) : ConnectionTestResult
    data object NotConfigured : ConnectionTestResult
}

/**
 * 上报主仓库：把「读设置 → 采样 → 门控 → 裁剪 → 发送 → 落状态」串成一条链。
 *
 * 几个关键约定：
 * 1. **先验配置**：地址/Token 缺失直接返回 [ReportOutcome.NotConfigured]，不发请求；
 * 2. **门控在采样之后**：因为电量变化类触发需要当前电量参与判定；
 * 3. **并发安全**：用 [Mutex] 串行化整条链——协程循环与闹钟可能同时进来，
 *    没有互斥就会出现「两次都读到旧 lastSuccessAt，于是发了两条」；
 * 4. **失败分类决定后续**：不可重试的错误立即写 `lastErrorKind` 让 UI 提示，
 *    可重试的错误压入离线队列等下次机会。
 */
class StatusRepository(
    private val settingsStore: SettingsStore,
    private val runtimeStateStore: RuntimeStateStore,
    private val historyStore: HistoryStore,
    private val api: StatusApi,
    private val deviceReader: DeviceStateReader,
    private val json: Json,
    private val appVersion: String,
) {

    private val mutex = Mutex()

    /** 设置与运行时状态的合并视图，供 UI 一次性订阅 */
    val uiState: Flow<Pair<AppSettings, RuntimeState>> =
        combine(settingsStore.settings, runtimeStateStore.state) { settings, runtime -> settings to runtime }

    /** 当前生效间隔（含写预算降频） */
    suspend fun effectiveIntervalMs(): Long {
        val settings = settingsStore.settings.first()
        val runtime = runtimeStateStore.snapshot()
        val writes = WriteBudget.normalizeWrites(
            runtime.writesToday,
            runtime.writesDayStartUtc,
            System.currentTimeMillis()
        )
        return WriteBudget.effectiveIntervalMs(settings.intervalMs, writes)
    }

    /** UI 判断「是否已触发每日额度保护」 */
    suspend fun isThrottled(): Boolean {
        val runtime = runtimeStateStore.snapshot()
        val writes = WriteBudget.normalizeWrites(
            runtime.writesToday,
            runtime.writesDayStartUtc,
            System.currentTimeMillis()
        )
        return WriteBudget.isThrottled(writes)
    }

    /**
     * 执行一次上报。[trigger] 决定门控规则（见 [ReportGate]）；
     * 用户在首页点「立即上报」用 [ReportTrigger.MANUAL]，绕过一切节流。
     */
    suspend fun report(trigger: ReportTrigger): ReportOutcome = mutex.withLock {
        val settings = settingsStore.settings.first()
        // 开关关掉后不该再自动上报：START_STICKY 让服务可能被系统重新拉起，
        // 那条路径（无 action → BOOT）会绕过一切节流发一条，用户看到的就是
        // 「我明明关了，怎么还在上报」。MANUAL 例外——那是用户当场按的按钮。
        if (!settings.enabled && trigger != ReportTrigger.MANUAL) {
            DebugLog.i(TAG, "Skipped: reporting disabled (${trigger.name})")
            return@withLock ReportOutcome.Skipped("disabled")
        }
        if (!settings.configured) {
            DebugLog.i(TAG, "Skipped: endpoint/token not configured")
            return@withLock ReportOutcome.NotConfigured
        }

        val normalized = EndpointNormalizer.normalize(settings.endpoint)
        val baseUrl = when (normalized) {
            is EndpointNormalizer.Result.Valid -> normalized.baseUrl
            else -> {
                DebugLog.w(TAG, "Skipped: invalid endpoint (${normalized::class.simpleName})")
                return@withLock ReportOutcome.NotConfigured
            }
        }

        val now = System.currentTimeMillis()
        val runtime = runtimeStateStore.snapshot()
        val writes = WriteBudget.normalizeWrites(runtime.writesToday, runtime.writesDayStartUtc, now)
        val intervalMs = WriteBudget.effectiveIntervalMs(settings.intervalMs, writes)

        val snapshot = readDeviceSnapshot(now)

        // 本地历史先落一条，再走门控：曲线的意义就是「没上报的那些时刻也在」，
        // 若放在门控之后，被挡下的那一轮（大多数轮次）就什么都不记，曲线会变成
        // 只有「电量涨了 3%」那些点的稀疏折线。
        // 只在本机写一份记录（Room 里一条 INSERT），不产生任何网络请求／KV 写，因此不必计入写预算。
        // 值跟上一条一样且不到 9 分钟时 `record()` 自己会跳过。
        if (settings.historyEnabled) {
            // 本地历史**绝不能连累心跳**：磁盘写失败（空间不足、存储损坏）时
            // 只丢一个画图的点，该发的那条心跳照样发。
            runCatching {
                historyStore.record(
                    BatterySample(t = now, b = snapshot.batteryPercent, c = snapshot.charging)
                )
            }.onFailure { DebugLog.w(TAG, "History sample dropped: ${it.message}") }
        }

        val gate = GateInput(
            trigger = trigger,
            now = now,
            lastSuccessAt = runtime.lastSuccessAt,
            lastAttemptAt = runtime.lastAttemptAt,
            lastBatteryPercent = runtime.lastBatteryPercent,
            currentBatteryPercent = snapshot.batteryPercent,
            lastBatteryChangeAt = runtime.lastBatteryChangeAt,
            hasPending = runtime.hasPending,
            effectiveIntervalMs = intervalMs,
        )

        if (!ReportGate.shouldReport(gate)) {
            // 被门控挡下也要更新本地电量基准，否则下次仍按旧基准算差值
            runtimeStateStore.recordBatterySample(now, snapshot.batteryPercent)
            return@withLock ReportOutcome.Skipped("gate")
        }

        val fields = FieldOptions(
            includeBattery = settings.includeBattery,
            includeCharging = settings.includeCharging,
            includeTemperature = settings.includeTemperature,
            includeNetwork = settings.includeNetwork,
            deviceName = settings.deviceName,
        )

        val seq = runtimeStateStore.nextSeq()
        val payload = HeartbeatPayloadFactory.build(snapshot, fields, appVersion, seq)

        runtimeStateStore.recordAttempt(now)
        DebugLog.i(TAG, "Reporting (${trigger.name}) battery=${payload.batteryPercent}")

        val result = api.sendHeartbeat(baseUrl, settings.token, payload.toDto())

        when (result) {
            is ApiResult.Success -> {
                runtimeStateStore.recordSuccess(
                    now = System.currentTimeMillis(),
                    batteryPercent = payload.batteryPercent,
                    writesToday = writes + 1,
                    writesDayStartUtc = WriteBudget.utcDayStart(now),
                )
                DebugLog.i(TAG, "Reported OK (HTTP ${result.httpCode})")
                ReportOutcome.Success(result.value.receivedAt)
            }

            is ApiResult.Failure -> {
                val error = result.error
                runtimeStateStore.recordFailure(error::class.simpleName ?: "Unknown")
                if (error.retryable) {
                    // 压队列：无意义差异就不覆盖，避免恢复后一次补发几十条
                    enqueuePending(payload)
                }
                DebugLog.e(TAG, "Report failed: ${describe(error)}")
                ReportOutcome.Failed(error)
            }
        }
    }

    /**
     * 记一次「已消耗的 KV 写」。
     *
     * 三条会产生 KV 写的路径（心跳 [report]、补发 [flushPending]、心情 [sendMood]）
     * **必须全部调用它**：漏掉任何一条，[WriteBudget] 的降频保护就会低估真实用量，
     * 于是「本地显示没超、服务端额度先爆」——心跳开始收 500，而 App 的兜底始终没触发。
     * 设置页的「连接测试」也会真发一条心跳（见 [testConnection]），同样要计。
     *
     * 只动计数，不动成功时刻（心情写成功不等于心跳成功，见 RuntimeStateStore.incrementWrites）。
     */
    private suspend fun recordKvWrite(runtime: RuntimeState, now: Long) {
        val writes = WriteBudget.normalizeWrites(runtime.writesToday, runtime.writesDayStartUtc, now)
        runtimeStateStore.incrementWrites(
            writesToday = writes + 1,
            writesDayStartUtc = WriteBudget.utcDayStart(now),
        )
    }

    /** 发送心情（内容未变则不发送——服务端不做读-比较-写，去重必须在客户端） */
    suspend fun sendMood(text: String, emoji: String? = null): ReportOutcome = mutex.withLock {
        val settings = settingsStore.settings.first()
        if (!settings.configured) return@withLock ReportOutcome.NotConfigured
        if (!settings.moodEnabled) return@withLock ReportOutcome.Skipped("mood-disabled")

        val normalized = EndpointNormalizer.normalize(settings.endpoint)
        val baseUrl = (normalized as? EndpointNormalizer.Result.Valid)?.baseUrl
            ?: return@withLock ReportOutcome.NotConfigured

        val runtime = runtimeStateStore.snapshot()
        val trimmed = text.trim()
        val trimmedEmoji = emoji?.trim()?.ifEmpty { null }
        if (trimmed.isEmpty()) return@withLock ReportOutcome.Skipped("empty")
        // 去重必须连表情一起比：只换表情、文字没变时，若只比文字就会被当成「没变」跳过，
        // 用户看到的是「点了发送，网页上的表情还是旧的」
        if (runtime.lastMoodText == trimmed && runtime.lastMoodEmoji == trimmedEmoji) {
            return@withLock ReportOutcome.Skipped("unchanged")
        }

        val result = api.sendMood(baseUrl, settings.token, MoodRequestDto(text = trimmed, emoji = trimmedEmoji))
        when (result) {
            is ApiResult.Success -> {
                runtimeStateStore.setLastMood(trimmed, trimmedEmoji)
                // 心情写入同样消耗一次 KV 写，必须计入预算：否则连发心情会绕过降频保护，
                // 真实写入量超出账号级 1000/天时心跳先被拒，而 App 还显示「没超」
                recordKvWrite(runtime, System.currentTimeMillis())
                DebugLog.i(TAG, "Mood sent OK")
                ReportOutcome.Success(result.value.updatedAt)
            }
            is ApiResult.Failure -> {
                DebugLog.e(TAG, "Mood failed: ${describe(result.error)}")
                ReportOutcome.Failed(result.error)
            }
        }
    }

    /**
     * 设置页的「连接测试」。
     *
     * 分两步：先 `GET /api/health` 判 Worker 可达（不碰 KV、零额度成本），
     * 再**真的发一次心跳**验证 Token —— 因为 health 无鉴权，单靠它无法发现 Token 填错。
     * 心跳会消耗一次 KV 写，因此这里明确用 [ReportTrigger.MANUAL] 且只发一次。
     */
    suspend fun testConnection(): ConnectionTestResult = mutex.withLock {
        val settings = settingsStore.settings.first()
        if (!settings.configured) return@withLock ConnectionTestResult.NotConfigured

        val normalized = EndpointNormalizer.normalize(settings.endpoint)
        val baseUrl = (normalized as? EndpointNormalizer.Result.Valid)?.baseUrl
            ?: return@withLock ConnectionTestResult.Failed(
                ReportError.Network("Invalid endpoint URL")
            )

        when (val health = api.health(baseUrl)) {
            is ApiResult.Failure -> {
                DebugLog.w(TAG, "Connection test: health failed (${describe(health.error)})")
                return@withLock ConnectionTestResult.Failed(health.error)
            }
            is ApiResult.Success -> {
                val code = health.httpCode
                val snapshot = readDeviceSnapshot()
                val payload = HeartbeatPayloadFactory.build(
                    snapshot = snapshot,
                    fields = FieldOptions(
                        includeBattery = settings.includeBattery,
                        includeCharging = settings.includeCharging,
                        includeTemperature = settings.includeTemperature,
                        includeNetwork = settings.includeNetwork,
                        deviceName = settings.deviceName,
                    ),
                    appVersion = appVersion,
                    seq = runtimeStateStore.nextSeq(),
                )
                when (val sent = api.sendHeartbeat(baseUrl, settings.token, payload.toDto())) {
                    is ApiResult.Success -> {
                        // 连接测试真的发了一次心跳＝真的消耗了一次 KV 写，必须计入预算。
                        // 不计的话：反复点「连接测试」就能绕过 WriteBudget 的降频保护，
                        // 本地显示「没超」而服务端额度先爆（与心情上报同一个坑）。
                        recordKvWrite(runtimeStateStore.snapshot(), System.currentTimeMillis())
                        DebugLog.i(TAG, "Connection test OK (HTTP $code → ${sent.httpCode})")
                        ConnectionTestResult.Ok(sent.httpCode)
                    }
                    is ApiResult.Failure -> when (val e = sent.error) {
                        is ReportError.Unauthorized -> {
                            DebugLog.w(TAG, "Connection test: token rejected")
                            ConnectionTestResult.Unauthorized(e.httpCode)
                        }
                        else -> {
                            DebugLog.w(TAG, "Connection test: heartbeat failed (${describe(e)})")
                            ConnectionTestResult.Failed(e)
                        }
                    }
                }
            }
        }
    }

    /** 失败退避等待时间（上层服务用它安排下一次尝试） */
    fun backoffFor(error: ReportError, attempts: Int): Long = when (error) {
        is ReportError.RateLimited -> RetryBackoff.delayFor429(error.retryAfterSeconds, attempts)
        else -> RetryBackoff.delayMs(attempts)
    }

    // ── 待发队列 ──

    private suspend fun enqueuePending(payload: HeartbeatPayload) {
        val runtime = runtimeStateStore.snapshot()
        val existing = runtime.pendingJson?.let { decodePending(it) }
        val merged = PendingQueue.merge(existing, payload) ?: return
        runtimeStateStore.setPending(json.encodeToString(HeartbeatPayload.serializer(), merged))
    }

    /**
     * 冲刷待发队列（网络恢复 / 周期上报时调用）。
     *
     * 发的是**队列里那份快照**（不是重新采样）：那才是「上一次真正想发的数据」，
     * 也保证了「只补发一条」的语义。
     */
    suspend fun flushPending(): ReportOutcome? = mutex.withLock {
        val settings = settingsStore.settings.first()
        if (!settings.configured) return@withLock null
        val normalized = EndpointNormalizer.normalize(settings.endpoint)
        val baseUrl = (normalized as? EndpointNormalizer.Result.Valid)?.baseUrl
            ?: return@withLock null

        val runtime = runtimeStateStore.snapshot()
        val pendingJson = runtime.pendingJson ?: return@withLock null
        val pending = decodePending(pendingJson) ?: run {
            runtimeStateStore.setPending(null)
            return@withLock null
        }

        val now = System.currentTimeMillis()
        val writes = WriteBudget.normalizeWrites(runtime.writesToday, runtime.writesDayStartUtc, now)

        when (val result = api.sendHeartbeat(baseUrl, settings.token, pending.toDto())) {
            is ApiResult.Success -> {
                runtimeStateStore.recordSuccess(
                    now = System.currentTimeMillis(),
                    batteryPercent = pending.batteryPercent,
                    writesToday = writes + 1,
                    writesDayStartUtc = WriteBudget.utcDayStart(now),
                )
                DebugLog.i(TAG, "Pending flushed OK")
                ReportOutcome.Success(result.value.receivedAt)
            }
            is ApiResult.Failure -> {
                runtimeStateStore.recordFailure(result.error::class.simpleName ?: "Unknown")
                if (!result.error.retryable) {
                    // 队列里的数据本身有问题（契约/凭据），留着只会一直失败
                    runtimeStateStore.setPending(null)
                }
                DebugLog.e(TAG, "Pending flush failed: ${describe(result.error)}")
                ReportOutcome.Failed(result.error)
            }
        }
    }

    private fun decodePending(raw: String): HeartbeatPayload? =
        runCatching { json.decodeFromString(HeartbeatPayload.serializer(), raw) }.getOrNull()

    /**
     * 采样设备状态并切到 IO 线程。
     *
     * [DeviceStateReader.snapshot] 内部是 `registerReceiver(ACTION_BATTERY_CHANGED)` 读粘性广播，
     * 同步的跨进程调用；上报链可能被 UI（点「立即上报」/「连接测试」）直接触发，
     * 若留在调用者线程上就会阻塞主线程。统一在这里切 IO，调用方不必各自记得加。
     */
    private suspend fun readDeviceSnapshot(now: Long = System.currentTimeMillis()): DeviceSnapshot =
        withContext(Dispatchers.IO) { deviceReader.snapshot(now) }

    private fun HeartbeatPayload.toDto() = HeartbeatRequestDto(
        schemaVersion = HeartbeatRequestDto.SCHEMA_VERSION,
        batteryPercent = batteryPercent,
        charging = charging,
        chargeSource = chargeSource,
        temperatureC = temperatureC,
        network = network,
        deviceName = deviceName,
        appVersion = appVersion,
        clientTs = clientTs,
        seq = seq,
    )

    private fun describe(error: ReportError): String = when (error) {
        is ReportError.Unauthorized -> "unauthorized (HTTP ${error.httpCode})"
        is ReportError.Contract -> "contract error (HTTP ${error.httpCode} ${error.code}: ${error.message})"
        is ReportError.EndpointNotFound -> "endpoint not found (404: ${error.message})"
        ReportError.PayloadTooLarge -> "payload too large"
        is ReportError.RateLimited -> "rate limited (retryAfter=${error.retryAfterSeconds})"
        is ReportError.Server -> "server error (HTTP ${error.httpCode})"
        is ReportError.Network -> "network error (${error.message})"
        is ReportError.Unknown -> "unknown (HTTP ${error.httpCode}: ${error.message})"
    }

    private companion object {
        const val TAG = "Report"
    }
}