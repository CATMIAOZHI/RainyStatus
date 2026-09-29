package com.rainy.status.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * 应用内语言偏好管理（自建轻量方案，不引入 appcompat 依赖）。
 *
 * 语言值约定：
 * - `null`（未设置）= 跟随系统
 * - `"zh"` = 简体中文（匹配 values-b+zh+Hans，BCP-47 写法，覆盖中国大陆等简体地区）
 * - `"zh-Hant"` = 繁体中文（匹配 values-b+zh+Hant，BCP-47 写法，覆盖台/港/澳所有繁体地区）
 * - `"en"` = English（匹配默认 values/ 英文资源）
 *
 * 注：AGP 9 资源合并器不接受 values-zh-Hans/values-zh-Hant 目录名，故用等价的
 * BCP-47 写法 values-b+zh+Hans / values-b+zh+Hant。
 *
 * Android 13+（TIRAMISU）与系统「应用语言」页同步：读写统一走 framework 的
 * [android.app.LocaleManager]，私有 SharedPreferences 仅在 API < 33 或 framework
 * 未设置时作为兜底，避免双源冲突。
 */
object LocaleManager {

    private const val PREFS_NAME = "locale_prefs"
    private const val KEY_LOCALE = "locale"

    /** 当前生效的语言代码；null = 跟随系统 */
    fun getLocaleCode(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales
            if (locales != null && !locales.isEmpty) {
                for (i in 0 until locales.size()) {
                    val l = locales.get(i)
                    when {
                        l.language == "zh" && l.script != "Hant" -> return "zh"
                        l.language == "zh" && l.script == "Hant" -> return "zh-Hant"
                        l.language == "en" -> return "en"
                    }
                }
                return null // 系统选了不支持的第三方语言 → 回退默认英文资源
            }
            // framework 未设置（跟随系统）：一次性迁移旧偏好后清除，保证单一权威源
            migrateLegacyPrefs(context)
            return null
        }
        val code = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LOCALE, null) ?: return null
        return code.takeIf { it == "zh" || it == "zh-Hant" || it == "en" }
    }

    /** 把 API < 33 时代写入的私有偏好一次性迁移到 framework 并清除 */
    private fun migrateLegacyPrefs(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val code = prefs.getString(KEY_LOCALE, null) ?: return
        context.getSystemService(android.app.LocaleManager::class.java)
            ?.setApplicationLocales(LocaleList.forLanguageTags(code))
        prefs.edit().remove(KEY_LOCALE).apply()
    }

    /** 保存语言偏好；传 null 表示跟随系统 */
    fun saveLocale(context: Context, code: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(android.app.LocaleManager::class.java)
                ?.setApplicationLocales(LocaleList.forLanguageTags(code ?: ""))
            return
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOCALE, code)
            .apply()
    }

    /** 把 base context 包装上应用语言配置；未设置偏好时原样返回 */
    fun wrapContext(base: Context): Context {
        val code = getLocaleCode(base) ?: return base
        val locale = when (code) {
            "zh" -> Locale.SIMPLIFIED_CHINESE
            "zh-Hant" -> Locale.forLanguageTag("zh-Hant")
            else -> Locale.ENGLISH
        }
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }
}
