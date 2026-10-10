package com.rainy.status.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.rainy.status.domain.history.MoodEvent

/**
 * 本机心情历史的一行（Room 实体）。
 *
 * 与 [MoodEvent] 字段一一对应，也与云端 `history_mood` 的
 * `updated_at / text / emoji` 三列同口径（见 `cloud/migrations/0001_history.sql`）。
 *
 * 主键直接用事件时刻 `at`：它既是天然去重的键（同一条事件重复写入会被忽略），
 * 也正好是「最新在前」那条查询要走的索引，不需要额外建索引。
 * 代价是将来若真要「从云端回填/导入」，撞上同一个 `at` 的行会被忽略。
 */
@Entity(tableName = "mood_events")
data class MoodEventEntity(
    @PrimaryKey val at: Long,
    val text: String,
    val emoji: String?,
) {
    fun toDomain(): MoodEvent = MoodEvent(at = at, text = text, emoji = emoji)
}

fun MoodEvent.toEntity(): MoodEventEntity = MoodEventEntity(at = at, text = text, emoji = emoji)
