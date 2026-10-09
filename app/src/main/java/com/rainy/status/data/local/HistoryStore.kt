package com.rainy.status.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.rainy.status.domain.history.BatteryHistory
import com.rainy.status.domain.history.BatterySample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 本机电量历史（首页曲线图的数据源）。
 *
 * **保留期：永久**——没有自动清理，只有用户在设置页点「清空本机记录」才会删。
 * 因此存储换成了 Room（一张 `battery_samples` 表）：
 * 10 分钟一条 ≈ 5 万条/年，若还压成一个 JSON blob 每次整段重写，
 * 一次采样就要重写几 MB，而且读一次要把全部历史拉进内存。
 * 现在写是一条 INSERT，读是按时间窗口查（[BatteryHistory.LOAD_WINDOW_MS]）。
 *
 * 三个约定：
 * 1. **查询永远带窗口**：图表最多画 7 天，绝不能出现「把所有历史读进内存」的调用；
 * 2. **写入前先判重**：值没变且距上一条不到 9 分钟就不记（[BatteryHistory.shouldRecord]），
 *    否则 60 秒间隔的用户一年能攒 50 万条；
 * 3. **读-判断-写不是原子的**，但只有 [com.rainy.status.data.repository.StatusRepository]
 *    的上报链在写，那条链上有 Mutex 串行化，不存在两个采样并发插入的窗口。
 *
 * 隐私：全部数据只在本机（`rainystatus_history.db`），**不上传、不参与云备份**
 * （`allowBackup="false"` 与 token 同一取舍）；开关默认关，用户显式打开后才开始记录。
 */
class HistoryStore(
    private val dao: BatterySampleDao,
    /** 只用于把早期构建存在 DataStore 里的那段历史一次性搬进 Room（见 [migrateLegacyHistory]） */
    private val legacyDataStore: DataStore<Preferences>,
    private val appContext: Context,
) {

    /** 图表窗口内的样本，按时间升序；窗口见 [BatteryHistory.LOAD_WINDOW_MS] */
    val samples: Flow<List<BatterySample>> =
        dao.observeRecent(BatteryHistory.LOAD_WINDOW_MS)
            .map { rows -> rows.map { it.toDomain() } }

    /** 已记录条数，设置页显示「共 N 条」 */
    val sampleCount: Flow<Int> = dao.observeCount()

    private val migrationLock = Mutex()
    private var legacyMigrated = false

    /** 记录一条采样；值与上一条相同且间隔太近时直接跳过（不写盘） */
    suspend fun record(sample: BatterySample) {
        ensureMigrated()
        if (!BatteryHistory.shouldRecord(dao.latest()?.toDomain(), sample)) return
        dao.insert(sample.toEntity())
    }

    /** 清空全部本机历史；**这是唯一会删数据的地方** */
    suspend fun clear() = dao.clear()

    /**
     * 迁移只在第一次用到时跑一次（与 RainyToken 的 `UsageCache.ensureMigrated` 同一做法）：
     * 放在构造函数里做会让 DI 图的构建变成异步操作，放在这里则是「谁先用到谁触发」。
     */
    private suspend fun ensureMigrated() {
        if (legacyMigrated) return
        migrationLock.withLock {
            if (legacyMigrated) return@withLock
            migrateLegacyHistory(appContext, legacyDataStore, dao)
            legacyMigrated = true
        }
    }
}