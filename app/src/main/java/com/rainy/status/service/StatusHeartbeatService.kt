package com.rainy.status.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.rainy.status.data.debug.DebugLog
import com.rainy.status.data.device.DeviceStateReader
import com.rainy.status.data.local.RuntimeStateStore
import com.rainy.status.data.local.SettingsStore
import com.rainy.status.data.repository.ReportOutcome
import com.rainy.status.data.repository.StatusRepository
import com.rainy.status.data.remote.ReportError
import com.rainy.status.domain.model.ReportTrigger
import com.rainy.status.di.ScopeQualifiers
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import javax.inject.Inject
import javax.inject.Named

/**
 * 常驻心跳前台服务（三通道里的 A 通道 + 事件入口）。
 *
 * 为什么是 `specialUse` 而不是 `dataSync`：见 `AndroidManifest.xml` 的注释——
 * Android 15 起 `dataSync` 24 小时累计只能跑 6 小时，常驻上报必然超时崩溃。
 *
 * 职责边界（刻意保持薄）：
 * - 前台化 + 通知刷新
 * - 亮屏期间的协程循环（息屏由 [AlarmScheduler] 兜底，两者共用 [StatusRepository] 的门控去重）
 * - 接收闹钟 / 通知动作 / 开机 / 电量等广播后触发一次上报
 * - 每次上报成功后重排下一个闹钟（自续）
 */
@AndroidEntryPoint
class StatusHeartbeatService : Service() {

    @Inject lateinit var repository: StatusRepository
    @Inject lateinit var settingsStore: SettingsStore
    @Inject lateinit var runtimeStateStore: RuntimeStateStore
    @Inject lateinit var deviceReader: DeviceStateReader

    /** 应用级作用域：用于「必须活过本服务生命周期」的收尾写入（见 STOP 分支） */
    @Inject @Named(ScopeQualifiers.APP) lateinit var appScope: CoroutineScope

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 亮屏循环任务；服务停止或关闭上报时取消 */
    private var loopJob: Job? = null

    /** 已失败次数，用于退避 */
    private var consecutiveFailures = 0

    /** 最近一次失败的类型：429 要看服务端给的 `Retry-After`，必须留着它才算得准 */
    private var lastError: ReportError? = null

    /** 事件接收器是否已注册（onStartCommand 可能被多次调用，必须防重复注册） */
    private var eventReceiverRegistered = false

    /** 动态注册的事件广播接收器（见 StatusEventReceiver 的说明：不用 Hilt，走回调） */
    private val eventReceiver by lazy {
        StatusEventReceiver(
            hasNetwork = { deviceReader.hasNetwork() },
            onTrigger = { trigger -> scope.launch { handleEvent(trigger) } },
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DebugLog.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        DebugLog.i(TAG, "onStartCommand action=$action")

        // 先前台化：必须在 5 秒内完成，否则 ANR/崩溃。
        // 这里用「可能为 null 的电量」快速构建通知，真实数据随后刷新。
        startForegroundCompat()

        when (action) {
            NotificationHelper.ACTION_STOP -> {
                DebugLog.i(TAG, "Stop requested from notification")
                // 用应用级作用域写设置：服务的 scope 马上就要被 cancel，
                // 在它里面写会导致「开关写着开着，服务已经停了」的状态不一致
                appScope.launch { settingsStore.setEnabled(false) }
                stopSelfSafely()
                return START_NOT_STICKY
            }

            NotificationHelper.ACTION_REPORT_NOW -> {
                scope.launch { triggerReport(ReportTrigger.MANUAL) }
            }

            AlarmScheduler.ACTION_ALARM_REPORT -> {
                scope.launch { triggerReport(ReportTrigger.PERIODIC) }
            }

            else -> {
                // 无 action（系统重启服务 / 开机拉起）：恢复循环
                scope.launch { triggerReport(ReportTrigger.BOOT) }
            }
        }

        startLoopIfNeeded()
        registerEventReceiverIfNeeded()
        return START_STICKY
    }

    /** 动态注册事件广播（只注册一次） */
    @Suppress("UnspecifiedRegisterReceiverFlag")
    private fun registerEventReceiverIfNeeded() {
        if (eventReceiverRegistered) return
        try {
            // 版本分支必须保留：`RECEIVER_NOT_EXPORTED` 是 API 33 才有的常量，
            // 而 minSdk 是 31（Android 12），所以 31/32 上要走不带 flag 的重载。
            // 只收本应用内部广播，因此用 RECEIVER_NOT_EXPORTED（不外泄给其他应用）。
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                    eventReceiver,
                    StatusEventReceiver.buildFilter(),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                registerReceiver(eventReceiver, StatusEventReceiver.buildFilter())
            }
            eventReceiverRegistered = true
            DebugLog.i(TAG, "Event receiver registered")
        } catch (e: Exception) {
            DebugLog.e(TAG, "Event receiver registration failed: ${e.message}")
        }
    }

    /**
     * 处理事件广播。
     *
     * 与 [triggerReport] 的区别：这里是「有理由地尝试」，所以先看队列——
     * 网络恢复时补发积压比新采一条更重要（积压那条才是断网期间的真实状态）。
     */
    private suspend fun handleEvent(trigger: ReportTrigger) {
        val settings = settingsStore.settings.first()
        if (!settings.enabled || !settings.configured) return

        when (trigger) {
            ReportTrigger.NETWORK -> repository.flushPending()
            ReportTrigger.CHARGING,
            ReportTrigger.UNLOCK,
            ReportTrigger.BATTERY_CHANGE -> triggerReport(trigger)
            else -> Unit
        }
    }

    /**
     * 前台化。
     *
     * **同步构建通知，绝不在这里做磁盘读**：`startForeground` 必须在 5 秒内完成，
     * 一旦超时系统直接抛 `ForegroundServiceDidNotStartInTimeException`。
     * 因此这里只用内存里的提示文案，真实电量/时间在 [refreshNotification] 里异步补上。
     *
     * `FOREGROUND_SERVICE_SPECIAL_USE` 在 API 34+ 需显式传 type，低版本传了会被忽略，
     * 所以做版本分支。
     */
    private fun startForegroundCompat() {
        val notification = NotificationHelper.buildServiceNotification(
            context = this,
            batteryPercent = null,
            lastSuccessAt = 0L,
            lowImportance = false,
        )
        val type = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        if (type != 0) {
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * 亮屏循环。
     *
     * 这里直接 `delay(interval)` 而不是自己按墙钟对齐相位：`delay` 基于协程调度器，
     * 设备休眠时协程不推进，醒来后继续——而「休眠期间没发心跳」本来就由
     * [AlarmScheduler] 的闹钟兜底，两边共用一个门控，不会重复写。
     * 每轮重新读一次间隔，用户改设置后立即生效。
     */
    private fun startLoopIfNeeded() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (isActive) {
                val settings = settingsStore.settings.first()
                if (!settings.enabled) {
                    DebugLog.i(TAG, "Loop stopped: reporting disabled")
                    break
                }

                val intervalMs = repository.effectiveIntervalMs()
                delay(intervalMs)

                // 每轮重新读配置：用户改间隔/关开关后立即生效，无需重启服务
                if (!isActive) break
                triggerReport(ReportTrigger.PERIODIC)
            }
        }
    }

    /** 一次上报 + 通知刷新 + 重排闹钟（自续） */
    private suspend fun triggerReport(trigger: ReportTrigger) {
        val outcome = try {
            if (trigger == ReportTrigger.NETWORK) {
                repository.flushPending() ?: repository.report(trigger)
            } else {
                repository.report(trigger)
            }
        } catch (e: Exception) {
            // 任何意外都不能让服务崩掉：常驻服务的崩溃会被系统降级处理
            DebugLog.e(TAG, "Report threw: ${e.message}")
            null
        }

        when (outcome) {
            is ReportOutcome.Success -> {
                consecutiveFailures = 0
                lastError = null
            }
            is ReportOutcome.Failed -> {
                consecutiveFailures += 1
                lastError = outcome.error
            }
            else -> Unit
        }

        refreshNotification()
        scheduleNextAlarm()
    }

    /**
     * 重排下一个兜底闹钟。
     *
     * 每轮都重排（而不是只排一次），好处是间隔变更、Doze 唤醒后相位都会自动跟着走；
     * 代价是每次排程会覆盖上一个同 PendingIntent 的闹钟，这正是不重复触发的原因。
     */
    private suspend fun scheduleNextAlarm() {
        val settings = settingsStore.settings.first()
        if (!settings.enabled) {
            AlarmScheduler.cancel(this)
            return
        }
        val base = repository.effectiveIntervalMs()
        // 连续失败时把下一次闹钟也一起退避，避免服务端故障期间反复唤醒耗电。
        // 退避曲线来自 [com.rainy.status.domain.report.RetryBackoff]：30s → ×2 → 上限 15 分钟，
        // ±20% 抖动；429 优先采用服务端给的 `Retry-After`（见 docs/api.md 的客户端重试约定）。
        // 低于 Doze 下限的值由 [AlarmScheduler] 抬到 9 分钟，这里不必再各自夹一遍。
        val interval = if (consecutiveFailures > 0) {
            repository.backoffFor(lastError ?: ReportError.Network("unknown"), consecutiveFailures)
        } else {
            base
        }
        AlarmScheduler.schedule(this, interval)
    }

    private suspend fun refreshNotification() {
        val settings = settingsStore.settings.first()
        val runtime = runtimeStateStore.snapshot()
        val battery = runCatching { deviceReader.snapshot().batteryPercent }.getOrNull()
        NotificationHelper.updateServiceNotification(
            context = this,
            batteryPercent = battery ?: runtime.lastBatteryPercent,
            lastSuccessAt = runtime.lastSuccessAt,
            lowImportance = !settings.notifyEnabled,
        )
    }

    private fun stopSelfSafely() {
        AlarmScheduler.cancel(this)
        loopJob?.cancel()
        unregisterEventReceiverIfNeeded()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun unregisterEventReceiverIfNeeded() {
        if (!eventReceiverRegistered) return
        runCatching { unregisterReceiver(eventReceiver) }
        eventReceiverRegistered = false
    }

    override fun onDestroy() {
        DebugLog.i(TAG, "Service destroyed")
        unregisterEventReceiverIfNeeded()
        scope.cancel()
        super.onDestroy()
    }

    /** 被系统杀掉时不做清理（保持 START_STICKY 的重启语义） */
    override fun onTaskRemoved(rootIntent: Intent?) {
        DebugLog.w(TAG, "Task removed")
        super.onTaskRemoved(rootIntent)
    }

    /**
     * 对外入口必须是 **public** companion：BootReceiver 与 UI 都要通过它拉起/停止服务，
     * `private companion object` 会让整个 Companion 私有，外部连 `start()` 都访问不到。
     * 只把常量标为 private 即可，不需要把整个伴生对象设为私有。
     */
    companion object {
        private const val TAG = "Service"
        private const val NOTIFICATION_ID = 1001

        /** 供外部（广播接收器、UI）拉起常驻服务 */
        fun start(context: android.content.Context) {
            val intent = Intent(context, StatusHeartbeatService::class.java)
            context.startForegroundService(intent)
        }

        /** 供外部请求停止（走通知里的同一条 STOP 路径，语义一致） */
        fun stop(context: android.content.Context) {
            val intent = Intent(context, StatusHeartbeatService::class.java).apply {
                action = NotificationHelper.ACTION_STOP
            }
            context.startService(intent)
        }
    }
}