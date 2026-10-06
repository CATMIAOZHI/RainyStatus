package com.rainy.status.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 运行时状态（与「用户设置」分开存）。
 *
 * 分开的理由：这些值每次上报都会变（写频高），而设置极少变；
 * 混在同一个 DataStore 里会让设置页的 Flow 频繁被无关变更唤醒。
 *
 * 保存内容：
 * - 上次成功 / 尝试上报时刻、最后一次用于比较的电量 → 供 [com.rainy.status.domain.report.ReportGate] 判定
 * - 今日写次数 + 当日 UTC 起点 → 供 [com.rainy.status.domain.report.WriteBudget] 兜底
 * - `seq` 递增序号、待发队列（压成一条）、上次发送的心情
 */
data class RuntimeState(
    val lastSuccessAt: Long = 0L,
    val lastAttemptAt: Long = 0L,
    val lastBatteryPercent: Int? = null,
    val lastBatteryChangeAt: Long = 0L,
    val writesToday: Int = 0,
    val writesDayStartUtc: Long = 0L,
    val seq: Long = 0L,
    /** 待发队列：JSON 字符串（最多一条，见 PendingQueue） */
    val pendingJson: String? = null,
    /** 上次成功发送的心情文本，用于客户端去重（服务端不做读-比较-写） */
    val lastMoodText: String? = null,
    /** 上次成功发送的心情表情。去重必须连它一起比：只改表情时文字没变，只比文字会被误判成「没变」 */
    val lastMoodEmoji: String? = null,
    /** 最近一次上报结果摘要，供首页展示 */
    val lastErrorKind: String? = null,
    /** 心情输入草稿：切换页面/重建后不丢用户正在打的字 */
    val moodDraft: String? = null,
    /** 心情表情草稿：与文字草稿一起存，避免「切个页面回来表情没了」 */
    val moodDraftEmoji: String? = null,
) {
    val hasPending: Boolean get() = pendingJson != null
}

class RuntimeStateStore(private val dataStore: DataStore<Preferences>) {

    val state: Flow<RuntimeState> = dataStore.data.map { prefs ->
        RuntimeState(
            lastSuccessAt = prefs[KEY_LAST_SUCCESS] ?: 0L,
            lastAttemptAt = prefs[KEY_LAST_ATTEMPT] ?: 0L,
            lastBatteryPercent = prefs[KEY_LAST_BATTERY],
            lastBatteryChangeAt = prefs[KEY_LAST_BATTERY_CHANGE] ?: 0L,
            writesToday = prefs[KEY_WRITES_TODAY] ?: 0,
            writesDayStartUtc = prefs[KEY_WRITES_DAY] ?: 0L,
            seq = prefs[KEY_SEQ] ?: 0L,
            pendingJson = prefs[KEY_PENDING],
            lastMoodText = prefs[KEY_LAST_MOOD],
            lastMoodEmoji = prefs[KEY_LAST_MOOD_EMOJI],
            lastErrorKind = prefs[KEY_LAST_ERROR],
            moodDraft = prefs[KEY_MOOD_DRAFT],
            moodDraftEmoji = prefs[KEY_MOOD_DRAFT_EMOJI],
        )
    }

    suspend fun snapshot(): RuntimeState = state.first()

    suspend fun recordAttempt(now: Long) = dataStore.edit { it[KEY_LAST_ATTEMPT] = now }

    /** 成功上报：更新成功时刻、写计数、清空待发与错误标记 */
    suspend fun recordSuccess(
        now: Long,
        batteryPercent: Int?,
        writesToday: Int,
        writesDayStartUtc: Long,
    ) = dataStore.edit { prefs ->
        prefs[KEY_LAST_SUCCESS] = now
        prefs[KEY_LAST_ATTEMPT] = now
        prefs[KEY_WRITES_TODAY] = writesToday
        prefs[KEY_WRITES_DAY] = writesDayStartUtc
        // 用 remove 而不是写空串：pendingJson 为 "" 时 hasPending 仍会是 true，队列就永远清不掉
        prefs.remove(KEY_PENDING)
        prefs.remove(KEY_LAST_ERROR)
        if (batteryPercent != null) {
            val previous = prefs[KEY_LAST_BATTERY]
            if (previous == null || previous != batteryPercent) {
                prefs[KEY_LAST_BATTERY_CHANGE] = now
            }
            prefs[KEY_LAST_BATTERY] = batteryPercent
        }
    }

    /** 电量变化但未上报（被门控挡下）时，只更新「当前电量」基准 */
    suspend fun recordBatterySample(now: Long, batteryPercent: Int?) = dataStore.edit { prefs ->
        // 读不到电量时不能拿它当基准：那会让下一次的差值判定从一个假值开始
        if (batteryPercent == null) return@edit
        val previous = prefs[KEY_LAST_BATTERY]
        if (previous != batteryPercent) {
            prefs[KEY_LAST_BATTERY_CHANGE] = now
        }
        prefs[KEY_LAST_BATTERY] = batteryPercent
    }

    /**
     * 只把今日写计数 +1，**不碰**成功时刻、不碰待发队列、不碰错误标记。
     *
     * 专给「产生了 KV 写、但不是一次心跳成功」的路径用（目前是心情上报）：
     * 复用 [recordSuccess] 会把心情也算成「刚刚心跳成功」，于是首页与通知里的
     * 「上次上报」会撒谎，掉线判定也会被心情上报推后。
     */
    suspend fun incrementWrites(writesToday: Int, writesDayStartUtc: Long) =
        dataStore.edit { prefs ->
            prefs[KEY_WRITES_TODAY] = writesToday
            prefs[KEY_WRITES_DAY] = writesDayStartUtc
        }

    suspend fun setPending(json: String?) = dataStore.edit { prefs ->
        if (json == null) prefs.remove(KEY_PENDING) else prefs[KEY_PENDING] = json
    }

    /** 取下一个 seq（本地递增，仅作诊断/乱序观察，云端不做严格排序） */
    suspend fun nextSeq(): Long {
        var next = 0L
        dataStore.edit { prefs ->
            next = (prefs[KEY_SEQ] ?: 0L) + 1
            prefs[KEY_SEQ] = next
        }
        return next
    }

    /**
     * 记录「上次成功发送的心情」= 文字 + 表情，两个 key 在**同一次** edit 里写。
     *
     * 为什么要一起写：去重比的是这个整体，分两次事务时若进程被杀，会留下
     * 「新文字 + 旧表情」的错配，下次发送要么多写一次 KV、要么被误判成「没变」跳过。
     */
    suspend fun setLastMood(text: String, emoji: String?) = dataStore.edit { prefs ->
        prefs[KEY_LAST_MOOD] = text
        if (emoji == null) prefs.remove(KEY_LAST_MOOD_EMOJI) else prefs[KEY_LAST_MOOD_EMOJI] = emoji
    }

    /**
     * 保存心情草稿（文字 + 表情）；空值按「无草稿」处理
     * （避免下次进页面先显示空串覆盖占位提示）。
     *
     * 两个字段在**同一次** edit 里写：分两次开会多一次磁盘事务，
     * 而且中途被杀进程会留下「有表情没文字」的半个草稿。
     */
    suspend fun setMoodDraft(text: String?, emoji: String?) = dataStore.edit { prefs ->
        val value = text?.takeIf { it.isNotEmpty() }
        if (value == null) prefs.remove(KEY_MOOD_DRAFT) else prefs[KEY_MOOD_DRAFT] = value
        val emojiValue = emoji?.takeIf { it.isNotEmpty() }
        if (emojiValue == null) prefs.remove(KEY_MOOD_DRAFT_EMOJI) else prefs[KEY_MOOD_DRAFT_EMOJI] = emojiValue
    }

    /**
     * 记录失败类型。[kind] 用 [com.rainy.status.data.remote.ReportError] 的简单类名，
     * 便于首页区分「Token 失效」与其他失败（Token 失效要显眼提示）。
     */
    suspend fun recordFailure(kind: String) = dataStore.edit { it[KEY_LAST_ERROR] = kind }

    private companion object {
        val KEY_LAST_SUCCESS = longPreferencesKey("last_success_at")
        val KEY_LAST_ATTEMPT = longPreferencesKey("last_attempt_at")
        val KEY_LAST_BATTERY = intPreferencesKey("last_battery_percent")
        val KEY_LAST_BATTERY_CHANGE = longPreferencesKey("last_battery_change_at")
        val KEY_WRITES_TODAY = intPreferencesKey("writes_today")
        val KEY_WRITES_DAY = longPreferencesKey("writes_day_start_utc")
        val KEY_SEQ = longPreferencesKey("seq")
        val KEY_PENDING = stringPreferencesKey("pending_json")
        val KEY_LAST_MOOD = stringPreferencesKey("last_mood_text")
        val KEY_LAST_MOOD_EMOJI = stringPreferencesKey("last_mood_emoji")
        val KEY_LAST_ERROR = stringPreferencesKey("last_error_kind")
        val KEY_MOOD_DRAFT = stringPreferencesKey("mood_draft")
        val KEY_MOOD_DRAFT_EMOJI = stringPreferencesKey("mood_draft_emoji")
    }
}

val Context.runtimeStateDataStore: DataStore<Preferences> by preferencesDataStore(name = "rainystatus_runtime")