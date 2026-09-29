package com.rainy.status.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rainy.status.data.debug.DebugLog
import com.rainy.status.data.local.SettingsStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 开机 / 重启后恢复常驻上报。
 *
 * 用 `BOOT_COMPLETED` 而不是 `LOCKED_BOOT_COMPLETED`：后者在用户解锁前触发，
 * 此时 DataStore（凭据加密存储）不可读，读配置会失败。
 *
 * 注意：**`specialUse` 类型不在 Android 15 的 BOOT_COMPLETED 禁启动名单里**
 * （禁启动名单只有 dataSync / camera / mediaPlayback / phoneCall / mediaProjection / microphone），
 * 这是当初选 specialUse 而非 dataSync 的第三个理由。
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsStore: SettingsStore

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        DebugLog.i(TAG, "Received $action")
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val settings = settingsStore.settings.first()
                if (!settings.enabled || !settings.autostart || !settings.configured) {
                    DebugLog.i(TAG, "Boot start skipped (enabled=${settings.enabled} autostart=${settings.autostart})")
                    return@launch
                }
                StatusHeartbeatService.start(appContext)
            } catch (e: Exception) {
                DebugLog.e(TAG, "Boot handling failed: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "Boot"
    }
}