package com.rainy.status.util

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * 保活相关状态查询与系统页跳转。
 *
 * 这里刻意不做「能不能启动 Activity」的预判（Android 11+ 包可见性会误判），
 * 一律 try/catch 启动 + 层层 fallback。
 */
object PermissionUtils {

    /** 是否已加入电池优化白名单 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 是否允许精确闹钟（Android 14 起默认拒绝） */
    fun canScheduleExactAlarms(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return am.canScheduleExactAlarms()
    }

    /** 通知运行时权限（Android 13+）；低版本恒为 true */
    fun hasNotificationPermission(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** 请求加入电池优化白名单（系统弹窗） */
    @SuppressLint("BatteryLife")
    fun openBatteryOptimizationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!startSafely(context, intent)) openAppDetails(context)
    }

    /** 请求精确闹钟权限（系统弹窗） */
    fun openExactAlarmSettings(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!startSafely(context, intent)) openAppDetails(context)
    }

    /** 应用详情页（最后的兜底跳转） */
    fun openAppDetails(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startSafely(context, intent)
    }

    /**
     * 尽量打开厂商的「自启动管理」页；找不到就退到应用详情页。
     *
     * 顺序为「厂商 action → 组件 → 应用详情页」。本机 HyperOS V816 的 shell
     * resolve-activity 可将 MIUI action 解析到自启动管理页；这不保证其他版本可用。
     * 厂商非标准入口可能随系统升级变化，启动失败时继续兜底。
     */
    fun openAutostartSettings(context: Context) {
        for (action in AUTOSTART_ACTIONS) {
            val intent = Intent(action).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (startSafely(context, intent)) return
        }
        for (component in AUTOSTART_COMPONENTS) {
            val intent = Intent().apply {
                this.component = component
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (startSafely(context, intent)) return
        }
        openAppDetails(context)
    }

    /**
     * 是否值得展示「自启动」这一行保活项。
     *
     * 按厂商标识启发式展示，不代表已检测到系统开关或后台限制。
     * 未命中的设备不展示本项；电池优化与闹钟引导仍独立提供。
     */
    fun needsOemAutostartGuide(): Boolean {
        val maker = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase(Locale.ROOT)
        return AGGRESSIVE_OEM_KEYWORDS.any { maker.contains(it) }
    }

    private val AUTOSTART_ACTIONS = listOf(
        // MIUI / HyperOS（实测可解析）
        "miui.intent.action.OP_AUTO_START",
        // 乐视
        "com.letv.android.letvsafe.autoboot",
    )

    private val AUTOSTART_COMPONENTS = listOf(
        ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"
        ),
        ComponentName(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
        ),
        ComponentName(
            "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity"
        ),
        ComponentName(
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
        ),
    )

    /** 厂商标识关键词（都按小写比较）：命中才显示自启动引导；须与 [AUTOSTART_COMPONENTS] 覆盖的厂商保持同步 */
    private val AGGRESSIVE_OEM_KEYWORDS = listOf(
        "xiaomi", "redmi", "poco",
        "huawei", "honor",
        "oppo", "realme", "oneplus",
        "vivo", "iqoo",
        "meizu", "letv", "smartisan",
    )

    /** 打开状态页 / 项目地址这类外部链接 */
    fun openUrl(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startSafely(context, intent)
    }

    private fun startSafely(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}
