package com.adnote.ink

import com.adnote.model.InkPoint
import com.adnote.model.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeGeometryTest {

    @Test
    fun testWidthAtPressureMapping() {
        val base = 4f
        // pressure 0 -> 0.4 * base = 1.6
        assertEquals(1.6f, StrokeGeometry.widthAt(base, 0f), 0.001f)
        // pressure 0.5 -> 0.8 * base = 3.2
        assertEquals(3.2f, StrokeGeometry.widthAt(base, 0.5f), 0.001f)
        // pressure 1.0 -> 1.2 * base = 4.8
        assertEquals(4.8f, StrokeGeometry.widthAt(base, 1.0f), 0.001f)
        // coerce outside 0..1
        assertEquals(1.6f, StrokeGeometry.widthAt(base, -0.5f), 0.001f)
        assertEquals(4.8f, StrokeGeometry.widthAt(base, 1.5f), 0.001f)
    }

    @Test
    fun testOutlineSinglePointOrEmpty() {
        val emptyStroke = Stroke(points = emptyList(), width = 2f)
        assertTrue(StrokeGeometry.outline(emptyStroke).isEmpty())

        val singleStroke = Stroke(points = listOf(InkPoint(10f, 10f)), width = 2f)
        assertTrue(StrokeGeometry.outline(singleStroke).isEmpty())
    }

    @Test
    fun testOutlineTwoPoints() {
        val stroke = Stroke(
            points = listOf(
                InkPoint(0f, 0f, 0.5f),
                InkPoint(100f, 0f, 0.5f)
            ),
            width = 10f // widthAt(10, 0.5) = 8, half = 4
        )
        val outline = StrokeGeometry.outline(stroke)
        // Dedupe retains 2 points, outline generates left (2 pts) + right reversed (2 pts) = 4 pts
        assertEquals(4, outline.size)
        // Along x-axis: normal is (0, 1) and (0, -1).
        // pt 0 left: (0 - 0*4, 0 + 1*4) = (0, 4)
        // pt 1 left: (100 - 0*4, 0 + 1*4) = (100, 4)
        // pt 1 right: (100 + 0*4, 0 - 1*4) = (100, -4)
        // pt 0 right: (0 + 0*4, 0 - 1*4) = (0, -4)
        assertEquals(0f, outline[0].x, 0.01f)
        assertEquals(4f, outline[0].y, 0.01f)
        assertEquals(100f, outline[1].x, 0.01f)
        assertEquals(4f, outline[1].y, 0.01f)
        assertEquals(100f, outline[2].x, 0.01f)
        assertEquals(-4f, outline[2].y, 0.01f)
        assertEquals(0f, outline[3].x, 0.01f)
        assertEquals(-4f, outline[3].y, 0.01f)
    }

    @Test
    fun testBounds() {
        val stroke = Stroke(
            points = listOf(
                InkPoint(10f, 20f),
                InkPoint(50f, 80f)
            ),
            width = 5f
        )
        val bounds = StrokeGeometry.bounds(stroke)
        // [minX-pad, minY-pad, maxX+pad, maxY+pad] where pad = width = 5
        assertEquals(5f, bounds[0], 0.001f) // 10 - 5
        assertEquals(15f, bounds[1], 0.001f) // 20 - 5
        assertEquals(55f, bounds[2], 0.001f) // 50 + 5
        assertEquals(85f, bounds[3], 0.001f) // 80 + 5
    }
}
