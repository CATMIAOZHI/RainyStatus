package com.rainy.status.domain.history

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 本机电量历史的一条采样。
 *
 * 字段名刻意短（`t` / `b` / `c`）：整段历史要压成一个 JSON 字符串存进 DataStore，
 * 2048 点乘三个键名，键名长短直接影响每次写盘的体积。字段含义写在下面，
 * 不在数据里存冗余信息。
 *
 * 与云端 `cloud/src/lib/history.ts` 的样本口径保持一致：
 * - [b] 为 `null` 表示**读不到电量**（不是 0%）；
 * - [c] 为 `null` 表示**不知道是否在充电**（不是「没充电」）。
 *   当前采样路径（[com.rainy.status.domain.model.DeviceSnapshot]）总能读到充电状态，
 *   保留可空是为了将来真读不到时不必把「未知」写成 `false`。
 */
@Serializable
data class BatterySample(
    /** 采样时刻（epoch ms，UTC） */
    val t: Long,
    /** 电量百分比 */
    val b: Int? = null,
    /** 是否在充电 */
    val c: Boolean? = null,
)

/** 首页图表的时间档 */
enum class HistoryRange {
    /** 最近 24 小时：折线 + 充电竖带 */
    H24,

    /** 最近 7 个本地日：每日电量区间条 + 每日充电次数 */
    D7,
}

/** 一个本地日（00:00–24:00，按设备时区）的汇总 */
data class DayRange(
    /** 该日 00:00 的 epoch ms（设备时区） */
    val dayStartMs: Long,
    /** 当日最低电量；当天没有任何样本时为 null */
    val min: Int?,
    /** 当日最高电量；当天没有任何样本时为 null */
    val max: Int?,
    /** 当日「充电开始」次数 */
    val chargeSessions: Int,
) {
    val hasData: Boolean get() = min != null && max != null
}

/**
 * 本地电量历史的纯逻辑：**不碰 DataStore、不碰 Android API**，全部可直接单测。
 *
 * 为什么要有这一层：曲线图最容易错的不是画法，而是「窗口怎么切、缺口怎么断、
 * 充电次数怎么数」这类口径问题。把它们挤在 Compose 里就没法验证了。
 */
object BatteryHistory {

    /** 保留 7 天（与网页端原始层同口径） */
    const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000

    /** 点数上限：7 天按 10 分钟一点约 1008 点，2048 留出事件触发的余量 */
    const val MAX_SAMPLES = 2048

    /**
     * 「值没变」时的最小记录间隔。
     *
     * 上报间隔可以选 60 秒，但电量一分钟内基本不会变；不做这个去重的话
     * 7 天会攒到 1 万点，抽稀把曲线压得看不出形状。9 分钟取自
     * [com.rainy.status.service.AlarmScheduler] 的 Doze 下限，与系统能保证的最密节奏一致。
     */
    const val SAME_VALUE_MIN_GAP_MS = 9L * 60 * 1000

    /** 折线断档阈值：与网页图表一致，超过 30 分钟没有样本就断开，不跨缺口连线 */
    const val LINE_GAP_MS = 30L * 60 * 1000

    /** 24 小时档的窗口长度 */
    const val H24_MS = 24L * 60 * 60 * 1000

    /** 7 天档的画法条数（含今天） */
    const val DAYS_IN_WEEK = 7

    /**
     * 追加一条采样，并顺带裁剪。
     *
     * **无变化时返回传入的那个实例本身**（而不是内容相同的新列表）：
     * 调用方（[com.rainy.status.data.local.HistoryStore]）用它判断「这次不用写盘」。
     * 这是本函数的契约，改动时别破坏——否则每次上报都会重写一遍整个 blob。
     */
    fun append(existing: List<BatterySample>, sample: BatterySample, now: Long): List<BatterySample> {
        val last = existing.lastOrNull()
        val unchanged = last != null && last.b == sample.b && last.c == sample.c
        if (unchanged && sample.t - last.t < SAME_VALUE_MIN_GAP_MS) return existing
        return prune(existing + sample, now)
    }

    /**
     * 按时间排序、丢弃 7 天前的样本、必要时抽稀到 [MAX_SAMPLES] 以内。
     *
     * 抽稀策略：**最近的一半原样保留，更早的一半隔点丢弃**。比「先算均值再写回」简单得多，
     * 也不会造出没出现过的电量值；代价是更早的极值可能被抹掉一点精度，
     * 但这只是画图，不是对账数据。
     */
    fun prune(samples: List<BatterySample>, now: Long): List<BatterySample> {
        val cutoff = now - RETENTION_MS
        var list = samples.filter { it.t >= cutoff }.sortedBy { it.t }
        while (list.size > MAX_SAMPLES) {
            val keepRecent = MAX_SAMPLES / 2
            val older = list.subList(0, list.size - keepRecent)
            val recent = list.subList(list.size - keepRecent, list.size)
            list = older.filterIndexed { index, _ -> index % 2 == 0 } + recent
        }
        return list
    }

    /** 窗口内的样本（**闭区间**，两端都算；输入需按时间升序） */
    fun inWindow(samples: List<BatterySample>, from: Long, to: Long): List<BatterySample> =
        samples.filter { it.t in from..to }

    /**
     * 「充电开始」次数：已知非充电（`false`）→ 充电 记 1 次。
     *
     * 与云端 `countChargeSessions`（`cloud/src/lib/history.ts`）**逐行同口径**：
     * - `c == null`（不知道）的样本**整条跳过**——既不参与判断，也**不覆盖**上一次已知状态。
     *   否则「充电中 → 未知 → 充电中」会被数成两次充电，而云端只数一次；
     * - [prevCharging] 是窗口开始前最后一条已知状态（见 [chargingStateBefore]）。
     *   必须传它，否则「跨窗口/跨日仍在充电」会被重复计一次。
     */
    fun chargeSessions(samples: List<BatterySample>, prevCharging: Boolean? = null): Int {
        var sessions = 0
        var prev: Boolean? = prevCharging
        for (sample in samples) {
            val charging = sample.c
            if (charging == null) continue
            if (charging && prev != true) sessions += 1
            prev = charging
        }
        return sessions
    }

    /**
     * [at] 之前最后一条**已知**充电状态；一条都没有时为 null。
     *
     * 不限回看长度，与云端取前置状态的做法一致：中间断档几天，
     * 「上一次知道的状态」仍然是那条真实的记录。
     */
    fun chargingStateBefore(samples: List<BatterySample>, at: Long): Boolean? =
        samples.lastOrNull { it.t < at && it.c != null }?.c

    /** 窗口内电量的最低/最高值；一个电量都没读到（或没有样本）时为 null */
    fun minMax(samples: List<BatterySample>): Pair<Int, Int>? {
        val values = samples.mapNotNull { it.b }
        if (values.isEmpty()) return null
        return values.min() to values.max()
    }

    /**
     * 把折线切成若干连续段：相邻两点间隔 > [gapMs] 就断开。
     *
     * 断档不连线是刻意的：手机息屏 / 断网期间没有数据，直接连过去会画出一条
     * 「电量平滑下降」的假线，而真相是「这段时间根本不知道」。
     */
    fun segments(samples: List<BatterySample>, gapMs: Long): List<List<BatterySample>> {
        val out = mutableListOf<List<BatterySample>>()
        var current = mutableListOf<BatterySample>()
        for (sample in samples) {
            if (current.isNotEmpty() && sample.t - current.last().t > gapMs) {
                out += current
                current = mutableListOf()
            }
            current += sample
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    /**
     * 充电竖带：连续 `c == true` 的样本合成一段 `[起, 止]`。
     *
     * 止点取法沿用网页图表：充电在「下一个样本」之前结束，因此带上界画到下一个样本的时刻；
     * 若充电一直持续到最后一个样本，则画到最后一条本身（只有一点时宽度为 0，由绘制侧补最小宽度）。
     */
    fun chargeBands(samples: List<BatterySample>): List<LongRange> {
        val bands = mutableListOf<LongRange>()
        var start: Long? = null
        for (i in samples.indices) {
            val charging = samples[i].c == true
            if (charging && start == null) start = samples[i].t
            val next = samples.getOrNull(i + 1)
            if (start != null && (next == null || !charging || next.c != true)) {
                val end = if (next != null && charging) next.t else samples[i].t
                bands += start..end
                start = null
            }
        }
        return bands
    }

    /**
     * 最近 [days] 个**本地日**的汇总（含今天，今天是不完整的一天）。
     *
     * 为什么按本地日而不是「从此刻往前 168 小时」切：横轴标签是日期，
     * 168 小时会把一天劈成两半，出现两根半截柱子、日期也对不上。
     *
     * 传 [ZoneId] 而不是内部取系统时区，是为了单测能钉住一个时区。
     */
    fun dailyRanges(
        samples: List<BatterySample>,
        now: Long,
        zone: ZoneId,
        days: Int = DAYS_IN_WEEK,
    ): List<DayRange> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (days - 1 downTo 0).map { offset ->
            val day = today.minusDays(offset.toLong())
            val start = dayStartMs(day, zone)
            val end = dayStartMs(day.plusDays(1), zone)
            val inDay = samples.filter { it.t >= start && it.t < end }
            val values = inDay.mapNotNull { it.b }
            DayRange(
                dayStartMs = start,
                min = values.minOrNull(),
                max = values.maxOrNull(),
                // 传「当天 00:00 之前的最后已知状态」：跨午夜仍在充电时不能在两天里各算一次
                chargeSessions = chargeSessions(inDay, chargingStateBefore(samples, start)),
            )
        }
    }

    /** 某个本地日 00:00 对应的 epoch ms */
    fun dayStartMs(day: LocalDate, zone: ZoneId): Long =
        day.atStartOfDay(zone).toInstant().toEpochMilli()
}
