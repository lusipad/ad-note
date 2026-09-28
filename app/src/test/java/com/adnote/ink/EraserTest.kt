package com.adnote.ink

import com.adnote.model.InkPoint
import com.adnote.model.PenType
import com.adnote.model.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
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

class PartialEraserTest {

    private fun line(id: String, y: Float, pen: PenType = PenType.FOUNTAIN) = Stroke(
        id = id,
        points = listOf(InkPoint(0f, y, 0.3f), InkPoint(100f, y, 0.9f)),
        width = 2f,
        color = "#1E3A8A",
        pen = pen,
    )

    @Test
    fun untouchedStrokesKeepIdentity() {
        val strokes = listOf(line("a", 50f))
        val out = Eraser.erasePartial(strokes, listOf(InkPoint(50f, 300f)), radius = 5f)
        assertSame(strokes, out)
    }

    @Test
    fun erasingTheMiddleSplitsTheStroke() {
        val strokes = listOf(line("a", 50f, PenType.PENCIL), line("b", 200f))
        // 竖直划过 a 的中间
        val out = Eraser.erasePartial(strokes, listOf(InkPoint(50f, 30f), InkPoint(50f, 70f)), radius = 5f)

        val pieces = out.filter { it.points.all { p -> p.y == 50f } }
        assertEquals(2, pieces.size)
        val left = pieces.minByOrNull { it.points.first().x }!!
        val right = pieces.maxByOrNull { it.points.first().x }!!
        assertTrue(left.points.last().x < 50f - 5f)
        assertTrue(right.points.first().x > 50f + 5f)
        assertEquals(0f, left.points.first().x, 0.001f)
        assertEquals(100f, right.points.last().x, 0.001f)
        // 片段保留原笔画属性，并分配新 id
        pieces.forEach {
            assertEquals("#1E3A8A", it.color)
            assertEquals(PenType.PENCIL, it.pen)
            assertEquals(2f, it.width, 0f)
            assertNotEquals("a", it.id)
        }
        // 没碰到的笔画原样保留
        assertTrue(out.any { it.id == "b" })
    }

    @Test
    fun erasingTheEndShortensTheStroke() {
        val out = Eraser.erasePartial(listOf(line("a", 50f)), listOf(InkPoint(100f, 50f)), radius = 10f)
        val piece = out.single()
        assertEquals(0f, piece.points.first().x, 0.001f)
        assertTrue(piece.points.last().x < 90f)
    }

    @Test
    fun coveringTheWholeStrokeRemovesIt() {
        val path = listOf(InkPoint(-10f, 50f), InkPoint(110f, 50f))
        assertTrue(Eraser.erasePartial(listOf(line("a", 50f)), path, radius = 5f).isEmpty())
    }

    @Test
    fun dotsAreRemovedWhenHit() {
        val dot = Stroke(id = "dot", points = listOf(InkPoint(10f, 10f)), width = 4f)
        assertTrue(Eraser.erasePartial(listOf(dot), listOf(InkPoint(12f, 10f)), radius = 3f).isEmpty())
    }

    @Test
    fun resampleInterpolatesPressure() {
        val dense = Eraser.resample(listOf(InkPoint(0f, 0f, 0f), InkPoint(10f, 0f, 1f)), step = 2f)
        assertTrue(dense.size >= 6)
        for (i in 1 until dense.size) {
            assertTrue(dense[i].x - dense[i - 1].x <= 2f + 0.001f)
            assertTrue(dense[i].pressure >= dense[i - 1].pressure)
        }
    }
}
