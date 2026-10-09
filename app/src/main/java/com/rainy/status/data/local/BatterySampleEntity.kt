package com.rainy.status.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.rainy.status.domain.history.BatterySample

/**
 * 本机电量历史的一行（Room 实体）。
 *
 * 与 [BatterySample] 字段一一对应，只是换了一张表来存：
 * 永久保留意味着历史会一直长（10 分钟一条 ≈ 5 万条/年），
 * 再把它压成一个 JSON blob 每次整段重写就不合适了——写入一条就是一条 INSERT。
 *
 * 主键直接用采样时刻 `t`：它既是天然去重的键（同一毫秒不会记两条），
 * 也正好是「按时间范围查」要走的那条索引，不需要额外建索引。
 */
@Entity(tableName = "battery_samples")
data class BatterySampleEntity(
    @PrimaryKey val t: Long,
    val b: Int?,
    val c: Boolean?,
) {
    fun toDomain(): BatterySample = BatterySample(t = t, b = b, c = c)
}

fun BatterySample.toEntity(): BatterySampleEntity = BatterySampleEntity(t = t, b = b, c = c)