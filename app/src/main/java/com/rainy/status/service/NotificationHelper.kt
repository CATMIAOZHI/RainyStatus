package com.rainy.status.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.rainy.status.MainActivity
import com.rainy.status.R
import com.rainy.status.util.DurationFormatter

/**
 * 常驻通知与前台服务通知的构建。
 *
 * 注意：**前台服务必须带通知**（Android 强制），`notifyEnabled=false` 只是把通知
 * 重要性降到最低（收进通知栏底部、不弹不响），**不能**取消它——取消就等于服务无法合法前台化。
 * 这一点必须在设置页文案里如实说明，不能让用户以为能彻底关掉。
 *
 * 为什么用**两个 channel id** 而不是改同一个 channel 的 importance：
 * channel 的重要性只在创建时生效，之后 `createNotificationChannel` 对已存在的
 * channel 一律忽略（用户可能在系统里手动调过，必须尊重）。用一个 id 就会导致
 * 「显示常驻通知」开关在 API 26+ 上毫无效果。两个 id 各自固定一个重要性，切换开关
 * 就是切换通知挂在哪个 channel 上，行为立刻可见。
 */
object NotificationHelper {

    /** 常驻、可见（默认） */
    const val CHANNEL_ID = "heartbeat"

    /** 最低重要性（用户关掉「显示常驻通知」时使用） */
    const val CHANNEL_ID_LOW = "heartbeat_low"

    private const val NOTIFICATION_ID = 1001

    /** 通知栏两个动作：立即上报 / 停止 */
    const val ACTION_REPORT_NOW = "com.rainy.status.action.REPORT_NOW"
    const val ACTION_STOP = "com.rainy.status.action.STOP"

    private const val REQUEST_OPEN = 1
    private const val REQUEST_REPORT = 2
    private const val REQUEST_STOP = 3

    /** 当前生效的 channel：按用户设置二选一 */
    fun channelId(lowImportance: Boolean): String = if (lowImportance) CHANNEL_ID_LOW else CHANNEL_ID

    fun ensureChannel(context: Context, lowImportance: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val id = channelId(lowImportance)
        // 已在系统里被用户调过的话不要覆盖（用户的选择优先）
        if (manager.getNotificationChannel(id) != null) return

        val channel = NotificationChannel(
            id,
            context.getString(
                if (lowImportance) R.string.notification_channel_name_silent
                else R.string.notification_channel_name
            ),
            if (lowImportance) NotificationManager.IMPORTANCE_MIN else NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(
                if (lowImportance) R.string.notification_channel_desc_silent
                else R.string.notification_channel_desc
            )
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 构建前台服务通知。
     *
     * @param batteryPercent 当前电量（null = 还没读到，显示为「—」而不是假的 0%）
     * @param lastSuccessAt 上次成功上报时刻（0 = 从未）
     */
    fun buildServiceNotification(
        context: Context,
        batteryPercent: Int?,
        lastSuccessAt: Long,
        lowImportance: Boolean,
    ): Notification {
        ensureChannel(context, lowImportance)

        val agoText = if (lastSuccessAt > 0L) {
            DurationFormatter.format(context, System.currentTimeMillis() - lastSuccessAt)
        } else {
            context.getString(R.string.home_never_reported)
        }
        val text = when {
            batteryPercent == null && lastSuccessAt <= 0L ->
                context.getString(R.string.notification_text_unknown)

            // 电量读不到（或用户关掉了电量的上报）时显「—」：假 0% 会让人以为手机快没电了
            batteryPercent == null ->
                context.getString(R.string.notification_text_no_battery, agoText)

            else ->
                context.getString(R.string.notification_text, batteryPercent, agoText)
        }

        return NotificationCompat.Builder(context, channelId(lowImportance))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(text)
            .setOngoing(true)
            // 每轮上报都会刷新这条通知；不静音 + onlyAlertOnce 仍可能在某些 ROM 上闪一下，
            // 直接静音最稳。可见性的差别由 channel 重要性（见上）负责。
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(if (lowImportance) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent(context))
            .addAction(0, context.getString(R.string.notification_action_report), reportNowIntent(context))
            .addAction(0, context.getString(R.string.notification_action_stop), stopIntent(context))
            .build()
    }

    /** 刷新已显示的服务通知；没有通知权限时静默跳过（服务本身仍合法运行） */
    @Suppress("MissingPermission")
    fun updateServiceNotification(
        context: Context,
        batteryPercent: Int?,
        lastSuccessAt: Long,
        lowImportance: Boolean,
    ) {
        if (!areNotificationsEnabled(context)) return
        val manager = NotificationManagerCompat.from(context)
        manager.notify(
            NOTIFICATION_ID,
            buildServiceNotification(context, batteryPercent, lastSuccessAt, lowImportance)
        )
    }

    fun areNotificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun reportNowIntent(context: Context): PendingIntent {
        val intent = Intent(context, StatusHeartbeatService::class.java).apply {
            action = ACTION_REPORT_NOW
        }
        return PendingIntent.getService(
            context,
            REQUEST_REPORT,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun stopIntent(context: Context): PendingIntent {
        val intent = Intent(context, StatusHeartbeatService::class.java).apply {
            action = ACTION_STOP
        }
        return PendingIntent.getService(
            context,
            REQUEST_STOP,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}