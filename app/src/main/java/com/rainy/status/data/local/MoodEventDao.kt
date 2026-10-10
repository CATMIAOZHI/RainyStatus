package com.rainy.status.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MoodEventDao {

    /**
     * 最新在前的最近 [limit] 条。
     *
     * 和电量那边不同，这里**不需要**「相对 MAX(at)」的自查询：心情没有滑动窗口，
     * 列表就是「最近几条」，固定 LIMIT 就够，也不会把整张表读进内存。
     */
    @Query("SELECT * FROM mood_events ORDER BY at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<MoodEventEntity>>

    /** 已记录条数（设置页显示「已记录 N 条」） */
    @Query("SELECT COUNT(*) FROM mood_events")
    fun observeCount(): Flow<Int>

    /** 主键冲突就忽略：同一条事件重复写入不会变成两行 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: MoodEventEntity): Long

    /** 清空本机心情历史（**只有用户手动点「清空」才会走到这里**；电量历史不受影响） */
    @Query("DELETE FROM mood_events")
    suspend fun clear(): Int
}