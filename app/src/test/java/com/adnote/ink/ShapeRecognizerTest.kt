package com.adnote.ink

import com.adnote.model.InkPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class ShapeRecognizerTest {

    /** 在 [a]→[b] 之间生成带轻微抖动的点。 */
    private fun wobbly(ax: Float, ay: Float, bx: Float, by: Float, n: Int = 20, amp: Float = 2f): List<InkPoint> =
        (0 until n).map { i ->
            val t = i.toFloat() / n
            val j = if (i % 2 == 0) amp else -amp
            InkPoint(ax + (bx - ax) * t + j * 0.3f, ay + (by - ay) * t + j, 0.5f)
        }

    @Test
    fun recognizesLine() {
        val pts = wobbly(0f, 0f, 300f, 20f) + InkPoint(300f, 20f)
        val r = ShapeRecognizer.recognize(pts)!!
        assertEquals(ShapeRecognizer.Kind.LINE, r.kind)
        assertEquals(0f, r.points.first().x, 3f)
        assertEquals(300f, r.points.last().x, 3f)
    }

    @Test
    fun recognizesAxisAlignedRectangle() {
        val pts = wobbly(0f, 0f, 200f, 0f) + wobbly(200f, 0f, 200f, 120f) +
            wobbly(200f, 120f, 0f, 120f) + wobbly(0f, 120f, 0f, 3f)
        val r = ShapeRecognizer.recognize(pts)!!
        assertEquals(ShapeRecognizer.Kind.RECTANGLE, r.kind)
        // 规整后的边完全水平/竖直
        assertTrue(r.points.all { it.x in -3f..203f && it.y in -3f..123f })
        assertEquals(r.points.first().x, r.points.last().x, 0.01f)
    }

    @Test
    fun recognizesTriangle() {
        val pts = wobbly(0f, 200f, 100f, 0f) + wobbly(100f, 0f, 200f, 200f) + wobbly(200f, 200f, 4f, 198f)
        assertEquals(ShapeRecognizer.Kind.TRIANGLE, ShapeRecognizer.recognize(pts)!!.kind)
    }

    @Test
    fun recognizesCircleAndEllipse() {
        fun ring(rx: Float, ry: Float) = (0 until 60).map { i ->
            val a = 2 * PI * i / 60
            val jitter = if (i % 3 == 0) 1.03f else 0.98f
            InkPoint(300f + rx * jitter * cos(a).toFloat(), 300f + ry * jitter * sin(a).toFloat())
        }
        assertEquals(ShapeRecognizer.Kind.CIRCLE, ShapeRecognizer.recognize(ring(100f, 104f))!!.kind)
        assertEquals(ShapeRecognizer.Kind.ELLIPSE, ShapeRecognizer.recognize(ring(160f, 80f))!!.kind)
    }

    @Test
    fun scribblesAndTinyStrokesAreNotShapes() {
        assertNull(ShapeRecognizer.recognize(listOf(InkPoint(0f, 0f), InkPoint(5f, 5f), InkPoint(8f, 2f), InkPoint(3f, 9f))))
        // 开放的「S」形曲线
        val s = (0..40).map { i ->
            val t = i / 40f
            InkPoint(t * 200f, 100f + sin(t * 2 * PI).toFloat() * 80f)
        }
        assertNull(ShapeRecognizer.recognize(s))
    }

    @Test
    fun holdDetection() {
        val moving = (0..10).map { InkPoint(it * 20f, 0f, t = it * 10L) }
        val still = (1..10).map { InkPoint(200f + (it % 2), 0f, t = 100L + it * 60L) }
        assertTrue(ShapeRecognizer.holdStartIndex(moving + still) in 9..11)
        assertEquals(-1, ShapeRecognizer.holdStartIndex(moving))
    }
}
