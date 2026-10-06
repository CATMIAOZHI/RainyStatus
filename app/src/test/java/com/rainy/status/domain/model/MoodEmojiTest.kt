package com.rainy.status.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表情字段的截断规则：必须与云端 `MAX_MOOD_EMOJI`（UTF-16 单元 ≤ 8）一致。
 * 这里的用例同时钉住「不切开代理对」这条——切坏了不会被服务端拒绝，只会显示成 `�`。
 */
class MoodEmojiTest {

    @Test
    fun `leading and trailing whitespace is trimmed`() {
        assertEquals("😀", MoodEmoji.sanitize("  😀\n"))
        assertEquals("😀😀", MoodEmoji.sanitize("\t😀😀 "))
        assertEquals("", MoodEmoji.sanitize("   "))
    }

    @Test
    fun `blank input produces no payload`() {
        assertNull(MoodEmoji.toPayload(""))
        assertNull(MoodEmoji.toPayload("   "))
        assertEquals("😀", MoodEmoji.toPayload("😀"))
    }

    @Test
    fun `four astral emoji fit exactly in eight units`() {
        val four = "😀😀😀😀"
        assertEquals(8, four.length)
        assertEquals(four, MoodEmoji.sanitize(four))
    }

    @Test
    fun `five astral emoji are truncated without splitting a surrogate pair`() {
        val result = MoodEmoji.sanitize("😀😀😀😀😀")
        assertEquals("😀😀😀😀", result)
        assertEquals(8, result.length)
        assertFalse(result.last().isHighSurrogate())
    }

    @Test
    fun `cut landing inside a surrogate pair steps back one unit`() {
        // 7 个 ASCII + 1 个 emoji = 9 个单元，第 8 个单元正好是高位代理
        val mixed = "abcdefg😀"
        assertEquals(9, mixed.length)
        val result = MoodEmoji.sanitize(mixed)
        assertEquals("abcdefg", result)
        assertTrue(result.none { it.isHighSurrogate() || it.isLowSurrogate() })
    }

    @Test
    fun `zwj emoji sequence of eight units is preserved`() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        assertEquals(8, family.length)
        assertEquals(family, MoodEmoji.sanitize(family))
    }

    @Test
    fun `bmp emoji counts by unit so the ninth is dropped`() {
        assertEquals("☔☔☔☔☔☔☔☔", MoodEmoji.sanitize("☔☔☔☔☔☔☔☔"))
        assertEquals("☔☔☔☔☔☔☔☔", MoodEmoji.sanitize("☔☔☔☔☔☔☔☔☔"))
    }

    @Test
    fun `isFull is false below the limit and true when the limit is reached`() {
        assertFalse(MoodEmoji.isFull(""))
        assertFalse(MoodEmoji.isFull("😀"))
        assertFalse(MoodEmoji.isFull("😀😀😀"))   // 6 个单元
        assertTrue(MoodEmoji.isFull("😀😀😀😀"))  // 正好 8 个单元
        assertTrue(MoodEmoji.isFull("☔☔☔☔☔☔☔☔"))
    }

    @Test
    fun `isFull still reports true when truncation steps back a unit`() {
        // 7 个 BMP + 1 个 astral = 9 个单元，第 8 个单元正好是高位代理 → 退一格后只有 7 个单元。
        // 判据必须看原始输入的 9 个单元：若改成拿截断结果（7）比长度，
        // 这个「明明打不进去又没有提示」的情形就会被漏掉，这条用例会转红
        val mixed = "☔☔☔☔☔☔☔😀"
        assertEquals(9, mixed.length)
        assertEquals(7, MoodEmoji.sanitize(mixed).length)
        assertTrue(MoodEmoji.isFull(mixed))
    }
}