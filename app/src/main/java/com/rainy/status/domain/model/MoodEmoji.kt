package com.rainy.status.domain.model

/**
 * 心情的表情字段规则。
 *
 * 与云端同一口径：上限按 **UTF-16 单元**计数（云端 `validate.ts` 用的是
 * `String.length`），不是码点。一个 😀 占 2 个单元，`👨‍👩‍👧` 这种带 ZWJ 的
 * 组合占 8 个。App 若按码点截断，就会「本地看着没超、发出去被 400 拒」，
 * 而用户只看到一句「发送失败」，无从判断哪里出了问题。
 *
 * 上限 8 个单元 ≈ 4 个普通 emoji：它只是网页上状态行前面那个小图标，
 * 再多也会把「心情」挤变形。
 */
object MoodEmoji {

    /** 与云端 `MAX_MOOD_EMOJI` 一致 */
    const val MAX_UNITS = 8

    /** 去掉首尾空白（含换行），并按上限截断 */
    fun sanitize(input: String): String = truncate(input.trim())

    /** 上报用：空串表示「不表情」，按契约传 null；其余交给调用方保证已 [sanitize] */
    fun toPayload(input: String): String? = sanitize(input).ifEmpty { null }

    /**
     * 输入是否已经到上限——UI 用它决定要不要提示「已达上限」。
     *
     * 判据刻意作用在**原始输入**上（trim 后直接比长度），而不是截断结果：
     * 跨上限的那一位正好切在代理对中间时会退一格，截断结果只有 7 个单元，
     * 拿它比长度就会漏掉「明明打不进去却没有任何提示」。
     */
    fun isFull(input: String): Boolean = input.trim().length >= MAX_UNITS

    /**
     * 按 UTF-16 单元截断，且**不切开代理对**。
     *
     * 切成半个代理对是个隐蔽的坑：那个残缺字符在 `length` 计数里仍占 1 个单元，
     * 所以能「合法」通过云端校验，但渲染出来是 `�`，属于肉眼可见的坏味道。
     */
    private fun truncate(value: String): String {
        if (value.length <= MAX_UNITS) return value
        val cut = value.substring(0, MAX_UNITS)
        // 末位是高位代理 → 说明正好切在代理对中间，退一格
        return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
    }
}
