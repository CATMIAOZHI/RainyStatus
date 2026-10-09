package com.rainy.status.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Database
import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 本机电量历史数据库。
 *
 * `version = 1` + `exportSchema = false`（与 RainyToken 的 `UsageDatabase` 一致）：
 * 不往仓库里塞 schema JSON。代价是**改表结构时必须手写 `Migration`**——
 * 这里存的是「永久保留」的历史，丢了就真没了，所以别改成 `fallbackToDestructiveMigration()`。
 *
 * 单个实例由 Hilt 的 `@Singleton` 保证（见 `di/AppModule.kt`），不自己写 `getInstance`。
 */
@Database(
    entities = [BatterySampleEntity::class],
    version = 1,
    exportSchema = false
)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun samples(): BatterySampleDao

    companion object {
        const val NAME = "rainystatus_history.db"
    }
}

/**
 * 旧版的本地历史容器（DataStore）。
 *
 * 数据已经改存 Room，这个 DataStore **只剩一个用途**：让 [migrateLegacyHistory]
 * 把早期构建写进去的 `samples_json` 读出来搬一次。搬完那个键就被删掉，
 * 之后它只是个空壳（保留它 = 不用改 [com.rainy.status.di.AppModule] 的限定符）。
 */
val Context.historyDataStore: DataStore<Preferences> by preferencesDataStore(name = "rainystatus_history")

/**
 * 一次性把旧的 DataStore JSON blob 搬进 Room（照 RainyToken 的 `migrateDataStoreToRoom` 做法）。
 *
 * 为什么值得留这段：换存储之前的历史是 DataStore 里的一个 JSON blob，直接换存储会把它悄悄丢掉。
 * 用 SharedPreferences 的标记位保证只跑一次（DataStore 被清过、标记还在时也不会重复搬）。
 *
 * **整段强制走 IO 线程**：这段的触发点是「升级后第一次记采样」，而那条链也可能从
 * 主线程发起（首页「立即上报」）；读 SharedPreferences 首次要解析 XML、
 * 再加一批 insert，放主线程就是几百毫秒的卡顿。批量的意义见 [BatterySampleDao.insertAll]。
 *
 * 顺序是刻意的：**一批搬完 → 打标记 → 最后才删旧 blob**。
 * 任何一个点崩掉都不会丢数据——标记前崩下次重跑（主键是时刻，重复的会被 IGNORE），
 * 标记后崩只是白占一点空间。
 *
 * @return true 表示这次真的搬了数据
 */
internal suspend fun migrateLegacyHistory(
    context: Context,
    dataStore: DataStore<Preferences>,
    dao: BatterySampleDao,
): Boolean = withContext(Dispatchers.IO) {
    val prefs = context.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
    if (prefs.getBoolean(KEY_MIGRATED, false)) return@withContext false

    val raw = dataStore.data.first()[LEGACY_SAMPLES_KEY]
    if (raw.isNullOrBlank()) {
        // 没有旧数据：也把标记打上，以后不再查
        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
        return@withContext false
    }

    val samples = HistoryCodec.decode(raw)
    if (samples.isNotEmpty()) {
        // 一批插完：主键是时刻，重复的会被 IGNORE 掉
        dao.insertAll(samples.map { it.toEntity() })
    }

    prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
    // 搬完就把旧 blob 删掉，别让它继续占地方
    dataStore.edit { it.remove(LEGACY_SAMPLES_KEY) }
    samples.isNotEmpty()
}

private const val MIGRATION_PREFS = "history_room_migration"
private const val KEY_MIGRATED = "migrated_to_room"
private val LEGACY_SAMPLES_KEY = stringPreferencesKey("samples_json")