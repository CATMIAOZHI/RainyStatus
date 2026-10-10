package com.rainy.status.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 用户设置（DataStore Preferences）。
 *
 * 设计要点：
 * 1. **不硬编码任何服务器地址与 token** —— 用户换服务器只改设置，不用重装；
 * 2. 默认值保守：启用开关默认关（先配好地址再开），温度/网络默认**不上报**；
 * 3. token 与 allowBackup=false 配合：应用数据不参与云备份，token 不会随备份外流。
 *    （不额外做 Keystore 加密：此 token 的能力仅限「写自己的状态页」，加密带来的
 *    密钥失效/迁移问题反而更可能弄丢用户配置；这是设计文档第 6 节的既定取舍。）
 */
data class AppSettings(
    val endpoint: String = "",
    val token: String = "",
    val enabled: Boolean = false,
    val intervalSeconds: Int = DEFAULT_INTERVAL_SECONDS,
    val autostart: Boolean = true,
    val notifyEnabled: Boolean = true,
    val includeBattery: Boolean = true,
    val includeCharging: Boolean = true,
    val includeTemperature: Boolean = false,
    val includeNetwork: Boolean = false,
    val deviceName: String = "",
    val moodEnabled: Boolean = true,
    /**
     * 是否在本机记录电量历史（首页曲线图的数据源）。
     *
     * **默认开**（2026-10 用户决定）：数据只留在本机、不上传，所以默认帮用户把曲线攒起来，
     * 想省电/嫌私密的人在设置里关掉即可；关掉只是不再记新的，已记录的样本保留。
     * 注意这个默认值是**在库里没有这条偏好时**才生效的：老用户如果手动关过，仍按关闭处理。
     */
    val historyEnabled: Boolean = true,
    /**
     * 是否在本机留一份心情历史（首页「最近的心情」的数据源）。
     *
     * 与 [historyEnabled] **分开**：两项写入时机完全不同（一个跟心跳，一个只在发送成功时），
     * 各自开关、各自清空。同样默认开、同样只留在本机、不上传。
     */
    val moodHistoryEnabled: Boolean = true,
    val themeMode: String = THEME_SYSTEM,
    /**
     * 用户**自述**已在系统里打开厂商的「自启动」开关。
     *
     * 注意与上面的 [autostart] 区分（两者名字像，但语义完全不同）：
     * - [autostart] 是 App 自己的开关，控制 `BootReceiver` 收到 BOOT_COMPLETED 后是否拉起服务；
     * - 本字段记录的是**系统层面**那个开关（HyperOS 的「自启动」、EMUI 的「自启动管理」…）。
     *
     * 项目没有可靠的跨厂商公开查询接口，也未采用厂商非公开接口。
     * 本机 shell 的 appops 观察不代表普通 App 的访问权限测试，不能推出绝对不可读。
     * 因此这里只保存用户确认，不是系统状态；false 表示未确认，不表示未开启。
     * 用户可随时撤销确认；确认或撤销都不修改系统开关，也不控制上报。
     */
    val autostartConfirmed: Boolean = false,
) {
    /** 地址与 Token 都填了才能上报 */
    val configured: Boolean get() = endpoint.isNotBlank() && token.isNotBlank()

    val intervalMs: Long get() = intervalSeconds * 1000L

    companion object {
        const val DEFAULT_INTERVAL_SECONDS = 600
        /** 可选间隔：60 / 300 / 600（默认）/ 900 秒 */
        val INTERVAL_CHOICES = listOf(60, 300, 600, 900)

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
    }
}

/**
 * 设置读写。
 *
 * 全部走 [Flow]：设置页改一个开关后，首页/服务/闹钟都能即时看到新值，
 * 不需要任何手动刷新或广播。
 */
class SettingsStore(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            endpoint = prefs[KEY_ENDPOINT] ?: "",
            token = prefs[KEY_TOKEN] ?: "",
            enabled = prefs[KEY_ENABLED] ?: false,
            intervalSeconds = (prefs[KEY_INTERVAL] ?: AppSettings.DEFAULT_INTERVAL_SECONDS)
                .takeIf { it in AppSettings.INTERVAL_CHOICES }
                ?: AppSettings.DEFAULT_INTERVAL_SECONDS,
            autostart = prefs[KEY_AUTOSTART] ?: true,
            notifyEnabled = prefs[KEY_NOTIFY] ?: true,
            includeBattery = prefs[KEY_INCLUDE_BATTERY] ?: true,
            includeCharging = prefs[KEY_INCLUDE_CHARGING] ?: true,
            includeTemperature = prefs[KEY_INCLUDE_TEMPERATURE] ?: false,
            includeNetwork = prefs[KEY_INCLUDE_NETWORK] ?: false,
            deviceName = prefs[KEY_DEVICE_NAME] ?: "",
            moodEnabled = prefs[KEY_MOOD_ENABLED] ?: true,
            historyEnabled = prefs[KEY_HISTORY_ENABLED] ?: true,
            moodHistoryEnabled = prefs[KEY_MOOD_HISTORY_ENABLED] ?: true,
            themeMode = prefs[KEY_THEME] ?: AppSettings.THEME_SYSTEM,
            autostartConfirmed = prefs[KEY_AUTOSTART_CONFIRMED] ?: false,
        )
    }

    suspend fun setEndpoint(value: String) = put { it[KEY_ENDPOINT] = value.trim() }
    suspend fun setToken(value: String) = put { it[KEY_TOKEN] = value.trim() }
    suspend fun setEnabled(value: Boolean) = put { it[KEY_ENABLED] = value }
    suspend fun setIntervalSeconds(value: Int) = put {
        it[KEY_INTERVAL] = value.takeIf { v -> v in AppSettings.INTERVAL_CHOICES }
            ?: AppSettings.DEFAULT_INTERVAL_SECONDS
    }
    suspend fun setAutostart(value: Boolean) = put { it[KEY_AUTOSTART] = value }
    suspend fun setNotifyEnabled(value: Boolean) = put { it[KEY_NOTIFY] = value }
    suspend fun setIncludeBattery(value: Boolean) = put { it[KEY_INCLUDE_BATTERY] = value }
    suspend fun setIncludeCharging(value: Boolean) = put { it[KEY_INCLUDE_CHARGING] = value }
    suspend fun setIncludeTemperature(value: Boolean) = put { it[KEY_INCLUDE_TEMPERATURE] = value }
    suspend fun setIncludeNetwork(value: Boolean) = put { it[KEY_INCLUDE_NETWORK] = value }
    suspend fun setDeviceName(value: String) =
        put { it[KEY_DEVICE_NAME] = value.trim().take(MAX_DEVICE_NAME_LENGTH) }
    suspend fun setMoodEnabled(value: Boolean) = put { it[KEY_MOOD_ENABLED] = value }

    /** 本地电量历史开关；关掉只是不再记录，已记录的样本保留（可随时再打开） */
    suspend fun setHistoryEnabled(value: Boolean) = put { it[KEY_HISTORY_ENABLED] = value }

    /** 本机心情历史开关；关掉只是不再记新的，已有记录保留（要删走「清空心情记录」） */
    suspend fun setMoodHistoryEnabled(value: Boolean) = put { it[KEY_MOOD_HISTORY_ENABLED] = value }

    /** 保存或撤销用户确认；不读取或修改系统「自启动」开关。 */
    suspend fun setAutostartConfirmed(value: Boolean) = put { it[KEY_AUTOSTART_CONFIRMED] = value }

    suspend fun setThemeMode(value: String) = put {
        it[KEY_THEME] = value.takeIf { v ->
            v == AppSettings.THEME_SYSTEM || v == AppSettings.THEME_LIGHT || v == AppSettings.THEME_DARK
        } ?: AppSettings.THEME_SYSTEM
    }

    private suspend fun put(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    companion object {
        /** 与云端校验一致：deviceName ≤ 32 字符（见 docs/api.md） */
        const val MAX_DEVICE_NAME_LENGTH = 32

        private val KEY_ENDPOINT = stringPreferencesKey("endpoint")
        private val KEY_TOKEN = stringPreferencesKey("token")
        private val KEY_ENABLED = booleanPreferencesKey("enabled")
        private val KEY_INTERVAL = intPreferencesKey("interval_seconds")
        private val KEY_AUTOSTART = booleanPreferencesKey("autostart")
        private val KEY_NOTIFY = booleanPreferencesKey("notify_enabled")
        private val KEY_INCLUDE_BATTERY = booleanPreferencesKey("field_battery")
        private val KEY_INCLUDE_CHARGING = booleanPreferencesKey("field_charging")
        private val KEY_INCLUDE_TEMPERATURE = booleanPreferencesKey("field_temperature")
        private val KEY_INCLUDE_NETWORK = booleanPreferencesKey("field_network")
        private val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
        private val KEY_MOOD_ENABLED = booleanPreferencesKey("mood_enabled")
        private val KEY_HISTORY_ENABLED = booleanPreferencesKey("history_enabled")
        private val KEY_MOOD_HISTORY_ENABLED = booleanPreferencesKey("mood_history_enabled")
        private val KEY_THEME = stringPreferencesKey("theme_mode")
        /** 系统「自启动」的可撤销用户确认，非系统状态；见 [AppSettings.autostartConfirmed] */
        private val KEY_AUTOSTART_CONFIRMED = booleanPreferencesKey("autostart_confirmed")
    }
}

/** 设置 DataStore 文件；与运行时状态分开，避免频繁写状态时干扰设置读写 */
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "rainystatus_settings")
