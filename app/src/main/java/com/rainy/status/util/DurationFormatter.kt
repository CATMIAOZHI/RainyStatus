package com.rainy.status.util

import android.content.Context
import com.rainy.status.R

/**
 * 把「距今多久」格式化成当前语言的文案。
 *
 * 保持纯静态工具（不依赖 Compose），通知栏与 Compose 页面共用同一套输出，
 * 避免两处各写一遍时间文案出现不一致。
 */
object DurationFormatter {

    /** @param elapsedMs 距今的毫秒数（负数按「刚刚」处理） */
    fun format(context: Context, elapsedMs: Long): String {
        if (elapsedMs < 60_000L) return context.getString(R.string.duration_just_now)
        val totalSeconds = elapsedMs / 1000L
        val minutes = totalSeconds / 60L
        if (minutes < 60L) return context.getString(R.string.duration_minutes, minutes.toInt())
        val hours = minutes / 60L
        if (hours < 24L) {
            val restMinutes = (minutes % 60L).toInt()
            return context.getString(R.string.duration_hours, hours.toInt(), restMinutes)
        }
        return context.getString(R.string.duration_days, (hours / 24L).toInt())
    }
}
