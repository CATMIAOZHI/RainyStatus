package com.rainy.status.data.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * APP 内调试日志，内存 ring buffer（默认 200 条）。
 *
 * 记录每次上报的触发源、响应码、失败分类与退避，用户可在设置页 → 调试日志 查看。
 * 只存内存：进程重启即清空，避免把请求细节长期落盘。
 */
object DebugLog {

    enum class Level(val label: String) { INFO("INFO"), WARN("WARN"), ERROR("ERROR") }

    /** 单调递增 id：给 Compose 的 `key` 用 */
    private val nextId = java.util.concurrent.atomic.AtomicLong(0)

    data class Entry(
        /**
         * 唯一标识。
         *
         * 必须用递增 id 而不是「时间戳 + 内容」拼字符串：同一毫秒内写入两条内容相同的
         * 日志完全可能（例如连续两次同样的失败），拼接键会撞车，而 Compose 的
         * `items(key = ...)` 遇到重复 key 会直接抛 `IllegalArgumentException`。
         */
        val id: Long,
        val timestamp: Long,
        val tag: String,
        val level: Level,
        val message: String,
    )

    private const val MAX_SIZE = 200
    private val deque = ConcurrentLinkedDeque<Entry>()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun log(tag: String, level: Level, message: String) {
        val entry = Entry(nextId.incrementAndGet(), System.currentTimeMillis(), tag, level, message)
        deque.addFirst(entry)
        while (deque.size > MAX_SIZE) deque.pollLast()
        _entries.value = deque.toList()
    }

    fun i(tag: String, message: String) = log(tag, Level.INFO, message)
    fun w(tag: String, message: String) = log(tag, Level.WARN, message)
    fun e(tag: String, message: String) = log(tag, Level.ERROR, message)

    fun clear() {
        deque.clear()
        _entries.value = emptyList()
    }

    /** `MM-dd HH:mm:ss` 时间戳，供日志行左侧展示 */
    fun formatTime(timestamp: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
            .format(Calendar.getInstance().apply { timeInMillis = timestamp }.time)
}
