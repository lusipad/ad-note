package com.adnote.ink

import com.adnote.model.InkPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class RulerTest {

    private val ruler = Ruler(cx = 500f, cy = 500f, angle = 0f, length = 600f, width = 100f)

    @Test
    fun containsAndCorners() {
        assertTrue(ruler.contains(500f, 520f))
        assertFalse(ruler.contains(500f, 600f))
        val c = ruler.corners()
        assertEquals(200f, c[0].first, 0.01f)
        assertEquals(450f, c[0].second, 0.01f)
    }

    @Test
    fun snapsToNearestEdge() {
        // 在上边缘（y=450）附近画一条歪线
        val pts = listOf(InkPoint(300f, 440f), InkPoint(400f, 455f), InkPoint(600f, 445f))
        val snapped = ruler.snap(pts)!!
        assertTrue(snapped.all { kotlin.math.abs(it.y - 450f) < 0.01f })
        assertEquals(300f, snapped.first().x, 0.5f)
        assertEquals(600f, snapped.last().x, 0.5f)
    }

    @Test
    fun farStrokesAreNotSnapped() {
        assertNull(ruler.snap(listOf(InkPoint(300f, 300f), InkPoint(400f, 300f))))
    }

    @Test
    fun rotatedRulerSnapsAlongItsAxis() {
        val r = ruler.rotated((PI / 2).toFloat())
        // 旋转 90° 后尺边是 x=450 / x=550 的竖线
        val snapped = r.snap(listOf(InkPoint(555f, 300f), InkPoint(548f, 420f)))!!
        assertTrue(snapped.all { kotlin.math.abs(it.x - 550f) < 0.01f })
    }
}
