package com.rainy.status.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rainy.status.domain.history.BatteryHistory
import com.rainy.status.domain.history.BatterySample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 本机电量历史（首页曲线图的数据源）。
 *
 * 三个约定：
 * 1. **独立 DataStore**（`rainystatus_history`）：曲线数据每 10 分钟写一次、
 *    整段 blob 重写，和「设置」或「运行时状态」混在一个文件里会互相拖慢；
 *    分开后设置页的 Flow 也不会因为一次采样被唤醒。
 * 2. **读-改-写必须在同一次 [edit] 里**：`append` 依赖当前值。
 *    先 `samples.first()` 再 `edit` 会丢更新（两个采样点并发时后写的覆盖先写的）。
 * 3. 裁剪（7 天 / 上限点数）由 [BatteryHistory] 的纯函数负责，这里只管存取。
 *
 * 隐私：全部数据只在本机的 DataStore 里，**不上传、不参与云备份**
 * （`allowBackup="false"` 与其余 token 同一取舍）；开关默认关，
 * 由用户显式打开后才开始记录。
 */
class HistoryStore(private val dataStore: DataStore<Preferences>) {

    /** 已按时间升序的历史样本；最多 7 天 */
    val samples: Flow<List<BatterySample>> = dataStore.data.map { HistoryCodec.decode(it[KEY_SAMPLES]) }

    /**
     * 记录一条采样。
     *
     * [BatteryHistory.append] 在「值与上一条相同且还不到最小间隔」时会**原样返回传入的列表**，
     * 这里据此跳过写盘（见该函数的契约说明）。
     */
    suspend fun record(sample: BatterySample, now: Long) = dataStore.edit { prefs ->
        val existing = HistoryCodec.decode(prefs[KEY_SAMPLES])
        val next = BatteryHistory.append(existing, sample, now)
        if (next === existing) return@edit
        prefs[KEY_SAMPLES] = HistoryCodec.encode(next)
    }

    /** 清空全部本地历史 */
    suspend fun clear() = dataStore.edit { it.remove(KEY_SAMPLES) }

    private companion object {
        val KEY_SAMPLES = stringPreferencesKey("samples_json")
    }
}

/** 历史 DataStore 文件；与设置（rainystatus_settings）、运行时状态（rainystatus_runtime）分开 */
val Context.historyDataStore: DataStore<Preferences> by preferencesDataStore(name = "rainystatus_history")