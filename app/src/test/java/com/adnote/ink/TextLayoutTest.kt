package com.adnote.ink

import com.adnote.model.Page
import com.adnote.model.TextBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLayoutTest {

    private val measure: (String) -> Float = { it.length * 10f }

    @Test
    fun wrapsByWidthAndKeepsNewlines() {
        assertEquals(listOf("abcde", "fgh"), TextLayout.wrap("abcdefgh", 50f, measure))
        assertEquals(listOf("ab", "", "cd"), TextLayout.wrap("ab\n\ncd", 100f, measure))
    }

    @Test
    fun breaksWesternTextAtSpaces() {
        assertEquals(listOf("hello", "world"), TextLayout.wrap("hello world", 80f, measure))
    }

    @Test
    fun estimateTreatsCjkAsFullWidth() {
        assertEquals(40f, TextLayout.estimateWidth("中文", 20f), 0.01f)
        assertEquals(22f, TextLayout.estimateWidth("ab", 20f), 0.01f)
    }

    @Test
    fun hitTest() {
        val page = Page(width = 1000, height = 1000, texts = listOf(TextBox(id = "a", x = 100f, y = 100f, text = "标题", size = 40f)))
        assertEquals("a", TextLayout.hit(page, 120f, 120f)?.id)
        assertNull(TextLayout.hit(page, 600f, 600f))
        val b = TextLayout.bounds(page.texts[0], 1000)
        assertTrue(b[3] - b[1] >= 40f)
    }
}
