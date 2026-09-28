package com.adnote.ink

import com.adnote.model.InkPoint
import com.adnote.model.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LassoTest {

    private val square = listOf(InkPoint(0f, 0f), InkPoint(100f, 0f), InkPoint(100f, 100f), InkPoint(0f, 100f))

    @Test
    fun containsUsesEvenOddRule() {
        assertTrue(Lasso.contains(square, 50f, 50f))
        assertFalse(Lasso.contains(square, 150f, 50f))
        assertFalse(Lasso.contains(square, 50f, -1f))
    }

    @Test
    fun selectRequiresMostPointsInside() {
        val inside = Stroke(id = "in", points = listOf(InkPoint(10f, 10f), InkPoint(20f, 20f), InkPoint(30f, 30f)))
        val mostly = Stroke(id = "mostly", points = listOf(InkPoint(10f, 50f), InkPoint(50f, 50f), InkPoint(90f, 50f), InkPoint(150f, 50f)))
        val outside = Stroke(id = "out", points = listOf(InkPoint(200f, 200f), InkPoint(300f, 300f)))
        val barely = Stroke(id = "barely", points = listOf(InkPoint(90f, 50f), InkPoint(150f, 50f), InkPoint(200f, 50f)))

        val ids = Lasso.select(listOf(inside, mostly, outside, barely), square)
        assertEquals(setOf("in", "mostly"), ids)
    }

    @Test
    fun selectNeedsAPolygon() {
        val s = Stroke(id = "a", points = listOf(InkPoint(1f, 1f)))
        assertTrue(Lasso.select(listOf(s), square.take(2)).isEmpty())
    }

    @Test
    fun translateMovesOnlySelected() {
        val a = Stroke(id = "a", points = listOf(InkPoint(1f, 2f)))
        val b = Stroke(id = "b", points = listOf(InkPoint(5f, 5f)))
        val moved = Lasso.translate(listOf(a, b), setOf("a"), 10f, -2f)
        assertEquals(11f, moved[0].points[0].x, 0.001f)
        assertEquals(0f, moved[0].points[0].y, 0.001f)
        assertSame(b, moved[1])

        val list = listOf(a, b)
        assertSame(list, Lasso.translate(list, setOf("a"), 0f, 0f))
    }

    @Test
    fun recolorChangesOnlySelected() {
        val a = Stroke(id = "a", points = listOf(InkPoint(1f, 2f)))
        val b = Stroke(id = "b", points = listOf(InkPoint(5f, 5f)))
        val out = Lasso.recolor(listOf(a, b), setOf("b"), "#DC2626")
        assertEquals("#000000", out[0].color)
        assertEquals("#DC2626", out[1].color)
    }
}
