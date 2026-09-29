package com.rainy.status.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import com.rainy.status.data.debug.DebugLog
import com.rainy.status.domain.model.ReportTrigger

/**
 * 事件即时上报（三通道里的 C 通道）的广播入口。
 *
 * **刻意不用 Hilt 注入**：这个接收器是前台服务在运行时动态注册的，
 * 实例由服务自己 new 出来，而 `@AndroidEntryPoint` 只在「清单声明的组件
 * 由系统/Hilt 实例化」时才生效。所以这里用回调把事件交回服务
 * （服务的依赖是正常注入的），避免出现字段未注入的 NPE。
 *
 * 事件源与节流策略（见 `docs/design.md` 第 9.4 节）：
 * - **充电插拔 → 无条件立即上报**（低频、语义重要：别人正想看你是不是在充电）
 * - 电量变化 → 交 [com.rainy.status.domain.report.ReportGate] 判定（Δ≥3% 且间隔 ≥120s）
 * - 屏幕点亮 / 解锁 → 距上次成功 ≥ 间隔才补一次
 * - 网络恢复 → 有待发队列就立即冲
 *
 * 动态注册（而非清单静态注册）的理由：接收器随服务进程存活，服务被杀就自然
 * 停止，不会留下野生的唤醒源让系统反复拉起应用。
 */
class StatusEventReceiver(
    private val hasNetwork: () -> Boolean,
    private val onTrigger: (ReportTrigger) -> Unit,
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val trigger = triggerFor(action) ?: return
        DebugLog.i(TAG, "Event $action → ${trigger.name}")
        onTrigger(trigger)
    }

    private fun triggerFor(action: String): ReportTrigger? = when (action) {
        Intent.ACTION_POWER_CONNECTED,
        Intent.ACTION_POWER_DISCONNECTED -> ReportTrigger.CHARGING

        // 粘性广播：注册瞬间会立刻收到一次，靠 ReportGate 的电量差值判定挡掉伪事件
        Intent.ACTION_BATTERY_CHANGED -> ReportTrigger.BATTERY_CHANGE

        Intent.ACTION_USER_PRESENT -> ReportTrigger.UNLOCK

        // 只有真的恢复了可用网络才上报；断开事件没意义（发也发不出去）
        ConnectivityManager.CONNECTIVITY_ACTION -> if (hasNetwork()) ReportTrigger.NETWORK else null

        else -> null
    }

    companion object {
        private const val TAG = "Event"

        /**
         * 需要动态注册的 IntentFilter。
         *
         * 这几个广播在注册侧都不需要额外权限：`ACTION_BATTERY_CHANGED` /
         * `ACTION_POWER_*` / `ACTION_USER_PRESENT` 公开可收，
         * `CONNECTIVITY_ACTION` 动态注册也不需要 `CHANGE_NETWORK_STATE`。
         */
        @Suppress("DEPRECATION")
        fun buildFilter(): IntentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
    }
}