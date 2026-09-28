package com.adnote.ink

import com.adnote.model.InkPoint
import com.adnote.model.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScratchOutTest {

    private fun zigzag(x0: Float, x1: Float, yTop: Float, yBottom: Float, passes: Int): List<InkPoint> {
        val out = ArrayList<InkPoint>()
        val stepY = (yBottom - yTop) / passes
        for (i in 0..passes) {
            val x = if (i % 2 == 0) x0 else x1
            // 每一趟插几个中间点，像真实采样
            val prev = out.lastOrNull()
            if (prev != null) for (k in 1..4) {
                val t = k / 5f
                out += InkPoint(prev.x + (x - prev.x) * t, prev.y + stepY * t)
            }
            out += InkPoint(x, yTop + stepY * i)
        }
        return out
    }

    @Test
    fun detectsZigzag() {
        assertTrue(ScratchOut.isScratch(zigzag(0f, 120f, 0f, 60f, passes = 8)))
        assertFalse(ScratchOut.isScratch(listOf(InkPoint(0f, 0f), InkPoint(50f, 0f), InkPoint(100f, 0f), InkPoint(150f, 5f),
            InkPoint(200f, 0f), InkPoint(250f, 3f), InkPoint(300f, 0f), InkPoint(350f, 0f))))
    }

    @Test
    fun deletesOnlyStrokesUnderTheScribble() {
        val word = Stroke(id = "word", points = listOf(InkPoint(20f, 30f), InkPoint(100f, 30f)))
        val far = Stroke(id = "far", points = listOf(InkPoint(500f, 500f), InkPoint(600f, 500f)))
        val longLine = Stroke(id = "long", points = listOf(InkPoint(-800f, 40f), InkPoint(900f, 40f)))
        val hits = ScratchOut.targets(listOf(word, far, longLine), zigzag(0f, 120f, 10f, 60f, passes = 8))
        assertEquals(setOf("word"), hits)
    }

    @Test
    fun zigzagOnEmptyAreaIsANormalStroke() {
        val far = Stroke(id = "far", points = listOf(InkPoint(500f, 500f), InkPoint(600f, 500f)))
        assertTrue(ScratchOut.targets(listOf(far), zigzag(0f, 120f, 10f, 60f, passes = 8)).isEmpty())
    }
}
