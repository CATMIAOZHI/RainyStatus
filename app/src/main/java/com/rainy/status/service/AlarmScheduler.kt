package com.rainy.status.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.rainy.status.data.debug.DebugLog
import com.rainy.status.util.PermissionUtils

/**
 * 息屏兜底的定时通道（三通道中的 B 通道）。
 *
 * 为什么必须单独用闹钟：
 * - 前台服务里的协程 `delay` 在息屏后**不会推进**（CPU 睡了），亮屏才算数；
 * - WorkManager 最小周期 15 分钟，且 Doze 下 JobScheduler 全部停摆，不能当主通道；
 * - `setExactAndAllowWhileIdle` 能在 Doze 中唤醒（代价是系统限制同应用最低约 9 分钟）。
 *
 * 用**一次性自续**而不是 `setRepeating`：`setRepeating` 在 API 19+ 起就被系统降级为
 * 非精确，且间隔会被对齐到不保证的时刻，反而更不准。
 */
object AlarmScheduler {

    const val ACTION_ALARM_REPORT = "com.rainy.status.action.ALARM_REPORT"

    /**
     * Doze 下系统对同应用精确闹钟的实际下限约 9 分钟（低电耗模式可能 ~15 分钟）。
     * 因此请求间隔小于该值时，按这个下限排程——不谎报精度，也不浪费时间反复唤醒。
     */
    private const val DOZE_MIN_INTERVAL_MS = 9 * 60 * 1000L

    fun schedule(context: Context, intervalMs: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = System.currentTimeMillis() + intervalMs.coerceAtLeast(DOZE_MIN_INTERVAL_MS)
        val pendingIntent = alarmPendingIntent(context)

        val canExact = PermissionUtils.canScheduleExactAlarms(context)
        try {
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                // 没有精确闹钟权限时降级：仍然允许在 Doze 中唤醒，但时间不精确
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
            DebugLog.i(TAG, "Alarm scheduled in ${intervalMs / 1000}s (exact=$canExact)")
        } catch (_: SecurityException) {
            // 权限在排程瞬间被撤销（极少数），降级重试一次；再失败就只靠前台服务
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                DebugLog.w(TAG, "Exact alarm denied, degraded to inexact")
            } catch (e: Exception) {
                DebugLog.e(TAG, "Alarm scheduling failed: ${e.message}")
            }
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(alarmPendingIntent(context))
        DebugLog.i(TAG, "Alarm cancelled")
    }

    private fun alarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, StatusHeartbeatService::class.java).apply {
            action = ACTION_ALARM_REPORT
        }
        // FLAG_UPDATE_CURRENT 必须带：schedule/cancel 必须命中同一个 PendingIntent
        return PendingIntent.getService(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private const val REQUEST_CODE = 2001
    private const val TAG = "Alarm"
}
