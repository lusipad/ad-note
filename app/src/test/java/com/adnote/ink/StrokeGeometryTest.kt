package com.adnote.ink

import com.adnote.model.InkPoint
import com.adnote.model.PenType
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

class PenTypeGeometryTest {

    @Test
    fun penTypesMapPressureDifferently() {
        val light = 0f
        val heavy = 1f
        fun w(pen: PenType, p: Float) =
            StrokeGeometry.widthAt(Stroke(points = emptyList(), width = 10f, pen = pen), p)

        // 钢笔与旧版一致
        assertEquals(StrokeGeometry.widthAt(10f, 0.3f), w(PenType.FOUNTAIN, 0.3f), 0.001f)
        // 马克笔、荧光笔等宽
        assertEquals(10f, w(PenType.MARKER, light), 0.001f)
        assertEquals(10f, w(PenType.MARKER, heavy), 0.001f)
        assertEquals(10f, w(PenType.HIGHLIGHTER, 0.5f), 0.001f)
        // 毛笔的粗细变化幅度最大
        val brushRange = w(PenType.BRUSH, heavy) - w(PenType.BRUSH, light)
        for (pen in listOf(PenType.FOUNTAIN, PenType.BALLPOINT, PenType.PENCIL)) {
            assertTrue(brushRange > w(pen, heavy) - w(pen, light))
        }
        // 圆珠笔几乎不受压感影响
        assertTrue(w(PenType.BALLPOINT, heavy) - w(PenType.BALLPOINT, light) < 3f)
    }

    @Test
    fun opacityAndConstantWidthFlags() {
        assertTrue(PenType.HIGHLIGHTER.opacity < 0.5f)
        assertTrue(PenType.HIGHLIGHTER.constantWidth)
        assertTrue(PenType.MARKER.constantWidth)
        assertEquals(1f, PenType.FOUNTAIN.opacity, 0f)
        assertTrue(PenType.WRITING.none { it == PenType.HIGHLIGHTER })
    }

    @Test
    fun brushTapersAtBothEnds() {
        val points = (0..20).map { InkPoint(it * 10f, 0f, 1f) }
        val stroke = Stroke(points = points, width = 10f, pen = PenType.BRUSH)
        val outline = StrokeGeometry.outline(stroke)
        val n = points.size
        // 左侧轮廓第 i 个点的 y 即半宽
        val startHalf = outline[0].y
        val midHalf = outline[n / 2].y
        val endHalf = outline[n - 1].y
        assertTrue(startHalf < midHalf * 0.5f)
        assertTrue(endHalf < midHalf * 0.5f)
        assertEquals(1f, StrokeGeometry.taperFactor(10, 21), 0f)
    }

    @Test
    fun centerlineDropsDuplicatePoints() {
        val s = Stroke(points = listOf(InkPoint(0f, 0f), InkPoint(0f, 0f), InkPoint(5f, 5f)), pen = PenType.MARKER)
        assertEquals(2, StrokeGeometry.centerline(s).size)
    }

    @Test
    fun unionBounds() {
        val a = Stroke(points = listOf(InkPoint(0f, 0f)), width = 2f)
        val b = Stroke(points = listOf(InkPoint(50f, 80f)), width = 2f)
        val u = StrokeGeometry.bounds(listOf(a, b))!!
        assertEquals(-2f, u[0], 0.001f)
        assertEquals(82f, u[3], 0.001f)
        assertEquals(null, StrokeGeometry.bounds(emptyList<Stroke>()))
    }
}
