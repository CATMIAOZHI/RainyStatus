package com.rainy.status.domain.report

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 失败退避策略：初始 30 秒 → ×2 → 上限 15 分钟，带 ±20% 抖动。
 *
 * 抖动是必要的：多条重试（未来多设备/多通道）如果同步触发，会一起撞 KV 的
 * 同 key 写频限（1 次/秒）→ 429，抖动把它们错开。
 *
 * 与 `docs/api.md` 的「客户端重试约定」对应：
 * - 401/403、400/415、413 **不重试**（由 [RetryPolicy] 在上层判定），本类只负责算等待时间
 * - 429 若带 `Retry-After` 则优先用它（见 [delayFor429]）
 */
object RetryBackoff {

    const val INITIAL_MS = 30_000L
    const val MAX_MS = 15 * 60 * 1000L
    const val JITTER_RATIO = 0.2

    /**
     * 第 [attempts] 次失败之后应等待多久。
     *
     * @param attempts 已失败次数（1 = 第一次失败后）
     * @param random 便于测试注入确定性的随机源
     */
    fun delayMs(attempts: Int, random: Random = Random.Default): Long {
        if (attempts <= 0) return INITIAL_MS
        // 用位移而非 pow 浮点：attempts 可能很大，浮点溢出会得到 Infinity
        val shift = min(attempts - 1, 20)
        val base = min(MAX_MS, INITIAL_MS shl shift)
        return applyJitter(base, random)
    }

    /**
     * 429 的等待时间：有 `Retry-After` 就用它（秒），否则退回指数退避。
     *
     * `Retry-After` 不可信到「照抄」的程度——服务端可能给出一个极大值，
     * 因此仍然夹在 [[INITIAL_MS], [MAX_MS]] 区间内。
     */
    fun delayFor429(retryAfterSeconds: Long?, attempts: Int, random: Random = Random.Default): Long {
        if (retryAfterSeconds == null || retryAfterSeconds <= 0) return delayMs(attempts, random)
        val ms = retryAfterSeconds * 1000L
        return ms.coerceIn(INITIAL_MS, MAX_MS)
    }

    /** ±[JITTER_RATIO] 的对称抖动，结果至少 1 秒 */
    private fun applyJitter(base: Long, random: Random): Long {
        val delta = (base * JITTER_RATIO).toLong()
        if (delta <= 0L) return base
        val jittered = base + random.nextLong(-delta, delta + 1)
        return max(1_000L, jittered)
    }
}