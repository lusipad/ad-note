package com.adnote.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportTest {

    /** 文石 10.3 寸：1404 宽的页面，画布扣掉两行工具栏后比页面矮。 */
    private val vp = Viewport(viewW = 1404, viewH = 1650, pageW = 1404, pageH = 1872).clamped()

    @Test
    fun wholePageFitsAtHundredPercentSoPalmCannotScrollIt() {
        assertEquals(1650f / 1872f, vp.scale, 0.0001f)
        assertFalse(vp.scrollsVertically)
        // 页面水平居中，上下铺满
        assertTrue(vp.panX < 0f)
        assertEquals(0f, vp.panY, 0.01f)
        assertEquals(0f, vp.toPageY(0f), 0.01f)
        assertEquals(1872f, vp.toPageY(1650f), 0.5f)
        // 单指平移在 100% 时不改变视口
        assertEquals(vp, vp.panBy(0f, -300f))
    }

    @Test
    fun roundTrips() {
        assertEquals(350f, vp.toViewX(vp.toPageX(350f)), 0.01f)
        assertEquals(420f, vp.toViewY(vp.toPageY(420f)), 0.01f)
    }

    @Test
    fun longPagesFitWidthAndScroll() {
        val long = Viewport(viewW = 1404, viewH = 1650, pageW = 1404, pageH = 1872 * 3).clamped()
        assertEquals(1f, long.scale, 0.0001f)
        assertTrue(long.scrollsVertically)
        assertEquals(0f, long.panY, 0.01f)
    }

    @Test
    fun zoomKeepsFocusStill() {
        val z = vp.zoomBy(2f, 700f, 800f)
        assertEquals(2f, z.zoom, 0.001f)
        assertEquals(vp.toPageX(700f), z.toPageX(700f), 0.5f)
        assertEquals(vp.toPageY(800f), z.toPageY(800f), 0.5f)
    }

    @Test
    fun zoomAndPanAreClamped() {
        val z = vp.zoomBy(100f, 0f, 0f)
        assertEquals(Viewport.MAX_ZOOM, z.zoom, 0.001f)
        val far = z.panBy(-1e6f, -1e6f)
        assertEquals(1404 * far.scale - 1404, far.panX, 0.5f)
        val back = z.panBy(1e6f, 1e6f)
        assertEquals(0f, back.panX, 0.01f)
        assertEquals(0f, back.panY, 0.01f)
        assertFalse(vp.zoomBy(0.1f, 0f, 0f).isZoomed)
    }

    @Test
    fun landscapePagesAreCentered() {
        val landscape = Viewport(viewW = 700, viewH = 900, pageW = 1872, pageH = 1404).clamped()
        assertEquals(700f / 1872f, landscape.scale, 0.0001f)
        assertTrue(landscape.panY < 0f)
        assertFalse(landscape.scrollsVertically)
    }
}
