package com.rainy.status.data.local

import com.rainy.status.domain.history.MoodEvent
import com.rainy.status.domain.history.MoodTimeline
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 本机心情历史（首页「最近的心情」的数据源）。
 *
 * 只记**真的发出去的那一条**：草稿、被去重跳过的、发送失败的都不记——
 * 这本账记的是「我说过什么」，不是「我打过什么字」。
 *
 * 保留期与电量一致：**永久**，只有设置页「清空心情记录」会删；而且刻意**不与电量历史
 * 共用清空入口**——那个按钮删掉的曲线是再也回不来的，不该被「顺手清一下心情」带走。
 *
 * 与 [HistoryStore] 平行、共用同一个 [HistoryDatabase]。
 */
class MoodHistoryStore(private val dao: MoodEventDao) {

    /** 最新在前的最近几条；条数在 DAO 里截断，不把整张表读进内存 */
    fun recent(limit: Int = MoodTimeline.MAX_RECENT): Flow<List<MoodEvent>> =
        // 顺序与条数 DAO 已经做了（ORDER BY at DESC LIMIT），这里再走一遍纯逻辑是**防线**：
        // 将来有人动 SQL 时，首页不会先「看起来只是顺序怪怪的」再变成「列表莫名只有一条」
        dao.observeRecent(limit).map { rows ->
            MoodTimeline.newestFirst(rows.map { it.toDomain() }, limit)
        }

    /** 已记录条数，设置页显示用 */
    val count: Flow<Int> = dao.observeCount()

    /** 记一条；同一时刻重复写入会被 IGNORE 掉（幂等） */
    suspend fun record(event: MoodEvent) {
        dao.insert(event.toEntity())
    }

    /** 清空心情历史；**电量历史不受影响** */
    suspend fun clear() = dao.clear()
}