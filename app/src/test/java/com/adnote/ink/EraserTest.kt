package com.adnote.ink

import com.adnote.model.InkPoint
import com.adnote.model.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EraserTest {

    @Test
    fun testDistToSegment() {
        val a = InkPoint(0f, 0f)
        val b = InkPoint(10f, 0f)

        // Point directly above segment
        val p1 = InkPoint(5f, 5f)
        assertEquals(5f, Eraser.distToSegment(p1, a, b), 0.001f)

        // Point past start point a
        val p2 = InkPoint(-3f, 4f)
        assertEquals(5f, Eraser.distToSegment(p2, a, b), 0.001f)

        // Point past end point b
        val p3 = InkPoint(13f, 4f)
        assertEquals(5f, Eraser.distToSegment(p3, a, b), 0.001f)
    }

    @Test
    fun testHitStrokes() {
        val s1 = Stroke(
            id = "s1",
            points = listOf(InkPoint(0f, 50f), InkPoint(100f, 50f)),
            width = 4f
        )
        val s2 = Stroke(
            id = "s2",
            points = listOf(InkPoint(0f, 200f), InkPoint(100f, 200f)),
            width = 4f
        )

        // Eraser crosses s1 vertically from (50, 40) to (50, 60)
        val eraserCrossingS1 = listOf(InkPoint(50f, 40f), InkPoint(50f, 60f))
        val hits = Eraser.hitStrokes(listOf(s1, s2), eraserCrossingS1, radius = 5f)
        assertEquals(setOf("s1"), hits)

        // Eraser far away from both
        val eraserFar = listOf(InkPoint(500f, 500f))
        val noHits = Eraser.hitStrokes(listOf(s1, s2), eraserFar, radius = 10f)
        assertTrue(noHits.isEmpty())
    }

    @Test
    fun testHitSinglePointStroke() {
        val dot = Stroke(
            id = "dot",
            points = listOf(InkPoint(50f, 50f)),
            width = 4f
        )
        val eraserNear = listOf(InkPoint(53f, 50f))
        val hits = Eraser.hitStrokes(listOf(dot), eraserNear, radius = 5f)
        assertEquals(setOf("dot"), hits)
    }
}
