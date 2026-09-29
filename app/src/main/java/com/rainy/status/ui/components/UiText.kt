package com.rainy.status.ui.components

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * 可本地化的文本抽象。
 *
 * ViewModel 不持有本地化字符串，而是返回 [UiText]：
 * - [Resource]：引用字符串资源（可带格式化参数，参数也可以是 [UiText]，解析时递归展开）
 * - [Dynamic]：服务端/系统返回的未知文本，原样透传
 */
sealed interface UiText {

    data class Resource(
        @StringRes val resId: Int,
        val args: List<Any> = emptyList()
    ) : UiText

    data class Dynamic(val value: String) : UiText
}

/** 在非 Composable 上下文（协程/回调/通知）中解析为字符串 */
fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Resource -> {
        val flatArgs = args.map { arg -> if (arg is UiText) arg.resolve(context) else arg }
        context.getString(resId, *flatArgs.toTypedArray())
    }
    is UiText.Dynamic -> value
}

/** 在 Composable 上下文中解析为当前语言的字符串 */
@Composable
fun UiText.asString(): String = when (this) {
    is UiText.Resource -> {
        val flatArgs = args.map { arg -> if (arg is UiText) arg.asString() else arg }
        stringResource(resId, *flatArgs.toTypedArray())
    }
    is UiText.Dynamic -> value
}
