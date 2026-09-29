package com.rainy.status.data.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.rainy.status.domain.model.DeviceSnapshot

/**
 * 读取本机电量 / 充电 / 温度 / 网络类型。
 *
 * 只读系统公开 API，**不申请任何位置、电话状态、Wi-Fi 名称权限**：
 * - 网络类型用 [NetworkCapabilities] 判定（TRANSPORT_WIFI / TRANSPORT_CELLULAR …），
 *   不需要 `ACCESS_WIFI_STATE`，也就拿不到 SSID——这正是我们想要的隐私边界。
 */
class DeviceStateReader(private val context: Context) {

    fun snapshot(now: Long = System.currentTimeMillis()): DeviceSnapshot {
        val battery = readBattery()
        return DeviceSnapshot(
            batteryPercent = battery.percent,
            charging = battery.charging,
            chargeSource = battery.source,
            temperatureC = battery.temperatureC,
            network = readNetwork(),
            capturedAt = now,
        )
    }

    private data class BatteryInfo(
        val percent: Int?,
        val charging: Boolean,
        val source: String,
        val temperatureC: Double?,
    )

    /**
     * 一次 `ACTION_BATTERY_CHANGED` 粘性广播就能拿到全部电量信息，
     * 比分别调 BatteryManager 的多个属性更省事，也是官方推荐做法。
     *
     * 电量**读不到时返回 null 而不是 0**：0 会被当成真实读数写进 payload 与通知，
     * 而 null 会被云端渲染成「不显示」，两者语义完全不同。
     */
    private fun readBattery(): BatteryInfo {
        val intent: Intent? = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) {
            (level * 100f / scale).toInt().coerceIn(0, 100)
        } else {
            null
        }

        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged != 0

        val source = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> DeviceSnapshot.SOURCE_AC
            BatteryManager.BATTERY_PLUGGED_USB -> DeviceSnapshot.SOURCE_USB
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> DeviceSnapshot.SOURCE_WIRELESS
            else -> DeviceSnapshot.SOURCE_NONE
        }

        // EXTRA_TEMPERATURE 单位是 0.1°C（官方 API 约定）；读不到就 null，不给假 0.0
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        val temperatureC = if (tempTenths != Int.MIN_VALUE) tempTenths / 10.0 else null

        return BatteryInfo(percent, isCharging, source, temperatureC)
    }

    /**
     * 网络类型判定。
     *
     * 取**当前活动网络**的 transport；VPN 等叠加传输时优先报告底层实际承载
     * （VPN 走的是 Wi-Fi/蜂窝，报 transport 对用户更有意义）。
     * 无活动网络 → `none`；有网络但拿不到 transport → `unknown`。
     */
    private fun readNetwork(): String {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return DeviceSnapshot.NET_UNKNOWN
        val network = cm.activeNetwork ?: return DeviceSnapshot.NET_NONE
        val caps = cm.getNetworkCapabilities(network) ?: return DeviceSnapshot.NET_NONE

        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> DeviceSnapshot.NET_WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> DeviceSnapshot.NET_CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> DeviceSnapshot.NET_ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> {
                // VPN 之上再判底层传输；拿不到就保守报 unknown（不谎报成 Wi-Fi）
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) DeviceSnapshot.NET_WIFI else DeviceSnapshot.NET_UNKNOWN
            }
            else -> DeviceSnapshot.NET_UNKNOWN
        }
    }

    /** 当前是否有可用网络（用于「网络恢复」事件判定） */
    fun hasNetwork(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}