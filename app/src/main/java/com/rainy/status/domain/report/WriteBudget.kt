package com.rainy.status.domain.report

import kotlin.math.max

/**
 * 每日写预算守卫。
 *
 * 为什么需要：Cloudflare KV 免费写额度是 **1,000 / 天（整个账号级）**，
 * 10 分钟心跳 144 次占 14.4%。但用户可能选 60 秒间隔（1,440 次/天，**直接超额**），
 * 或者同账号下别的项目也在写。因此 App 侧必须有硬兜底，宁可降频也不能把额度打爆。
 *
 * 策略：
 * - 超过 [SOFT_LIMIT] 次 → 间隔强制拉长到 [THROTTLED_INTERVAL_MS]（15 分钟 → 96 次/天）
 * - 计数按 **UTC 日** 归零（与 Cloudflare 额度重置时刻一致，避免本地时区错位）
 */
object WriteBudget {

    /** 软上限：达到后自动降频。留了足够余量给心情上报与重试 */
    const val SOFT_LIMIT = 600

    /** 降频后的间隔：15 分钟 */
    const val THROTTLED_INTERVAL_MS = 15 * 60 * 1000L

    /**
     * 计算「当前生效的上报间隔」。
     *
     * @param configuredMs 用户配置的间隔
     * @param writesToday 今日已写次数
     */
    fun effectiveIntervalMs(configuredMs: Long, writesToday: Int): Long =
        if (writesToday >= SOFT_LIMIT) max(configuredMs, THROTTLED_INTERVAL_MS) else configuredMs

    /** 是否处于降频状态（UI 要提示用户） */
    fun isThrottled(writesToday: Int): Boolean = writesToday >= SOFT_LIMIT

    /**
     * 今日写次数归零判断：记录里的 `dayStartUtc` 与当前 UTC 日不同则归零。
     *
     * 不用本地时区：Cloudflare 的额度按 UTC 00:00 重置，用本地日会在
     * UTC+8 之类时区把「重置后的新额度」和「昨天的计数」混在一起算。
     */
    fun utcDayStart(now: Long): Long {
        // UTC 日长恒为 86_400_000 ms（无闰秒、无夏令时），且 epoch 原点就在 UTC 午夜，
        // 因此直接取模即可，不需要 Calendar/TimeZone（也就不会受设备时区影响）
        val dayMs = 86_400_000L
        return now - Math.floorMod(now, dayMs)
    }

    /** 跨 UTC 日则视为 0 */
    fun normalizeWrites(writes: Int, recordedDayStartUtc: Long, now: Long): Int =
        if (recordedDayStartUtc != utcDayStart(now)) 0 else writes
}