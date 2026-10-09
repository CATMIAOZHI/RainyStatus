package com.rainy.status.di

import android.content.Context
import androidx.room.Room
import com.rainy.status.BuildConfig
import com.rainy.status.data.device.DeviceStateReader
import com.rainy.status.data.local.BatterySampleDao
import com.rainy.status.data.local.HistoryDatabase
import com.rainy.status.data.local.HistoryStore
import com.rainy.status.data.local.RuntimeStateStore
import com.rainy.status.data.local.SettingsStore
import com.rainy.status.data.local.historyDataStore
import com.rainy.status.data.local.runtimeStateDataStore
import com.rainy.status.data.local.settingsDataStore
import com.rainy.status.data.remote.ApiJson
import com.rainy.status.data.remote.StatusApi
import com.rainy.status.data.repository.StatusRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Named
import javax.inject.Singleton

/**
 * 依赖装配。
 *
 * 惯例（沿用雨晴系列）：**一律用 `@Provides` 显式构造**，不用 `@Inject constructor`。
 * 原因是同模式的类达到 3+ 时 KSP 2.x 会误报，显式声明能彻底规避。
 *
 * 两个 DataStore 必须用 [Named] 区分——它们都是 `DataStore<Preferences>` 类型。
 */
object DataStoreQualifiers {
    const val SETTINGS = "dataStore.settings"
    const val RUNTIME_STATE = "dataStore.runtimeState"
    const val HISTORY = "dataStore.history"
}

/** 长于组件生命周期的协程作用域限定符 */
object ScopeQualifiers {
    const val APP = "scope.app"
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @Named(ScopeQualifiers.APP)
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    // 配置放在 ApiJson 里，好让单测用**同一份**配置（否则测试绿了线上仍可能不一样）
    fun provideJson(): Json = ApiJson.create()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = StatusApi.defaultClient()

    @Provides
    @Singleton
    fun provideStatusApi(client: OkHttpClient, json: Json): StatusApi = StatusApi(client, json)

    @Provides
    @Singleton
    @Named(DataStoreQualifiers.SETTINGS)
    fun provideSettingsDataStore(
        @ApplicationContext context: Context
    ) = context.settingsDataStore

    @Provides
    @Singleton
    @Named(DataStoreQualifiers.RUNTIME_STATE)
    fun provideRuntimeStateDataStore(
        @ApplicationContext context: Context
    ) = context.runtimeStateDataStore

    @Provides
    @Singleton
    @Named(DataStoreQualifiers.HISTORY)
    fun provideHistoryDataStore(
        @ApplicationContext context: Context
    ) = context.historyDataStore

    @Provides
    @Singleton
    fun provideSettingsStore(
        @Named(DataStoreQualifiers.SETTINGS) dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    ): SettingsStore = SettingsStore(dataStore)

    @Provides
    @Singleton
    fun provideRuntimeStateStore(
        @Named(DataStoreQualifiers.RUNTIME_STATE) dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    ): RuntimeStateStore = RuntimeStateStore(dataStore)

    @Provides
    @Singleton
    fun provideHistoryStore(
        dao: BatterySampleDao,
        @Named(DataStoreQualifiers.HISTORY) dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
        @ApplicationContext context: Context,
    ): HistoryStore = HistoryStore(dao, dataStore, context)

    @Provides
    @Singleton
    fun provideHistoryDatabase(
        @ApplicationContext context: Context
    ): HistoryDatabase = Room.databaseBuilder(
        context.applicationContext,
        HistoryDatabase::class.java,
        HistoryDatabase.NAME,
    ).build()

    @Provides
    @Singleton
    fun provideBatterySampleDao(database: HistoryDatabase): BatterySampleDao = database.samples()

    @Provides
    @Singleton
    fun provideDeviceStateReader(@ApplicationContext context: Context): DeviceStateReader =
        DeviceStateReader(context)

    @Provides
    @Singleton
    fun provideStatusRepository(
        settingsStore: SettingsStore,
        runtimeStateStore: RuntimeStateStore,
        historyStore: HistoryStore,
        api: StatusApi,
        deviceReader: DeviceStateReader,
        json: Json,
    ): StatusRepository = StatusRepository(
        settingsStore = settingsStore,
        runtimeStateStore = runtimeStateStore,
        historyStore = historyStore,
        api = api,
        deviceReader = deviceReader,
        json = json,
        // 直接用 BuildConfig：上报的 appVersion 必须与安装的版本一致，
        // 不能从设置里读（那样用户改一次就会永久写错）
        appVersion = BuildConfig.VERSION_NAME,
    )
}