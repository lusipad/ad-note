package com.adnote.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportTest {

    private val vp = Viewport(viewW = 700, viewH = 900, pageW = 1400, pageH = 1872).clamped()

    @Test
    fun fitsPageWidthAndRoundTrips() {
        assertEquals(0.5f, vp.scale, 0.0001f)
        assertEquals(700f, vp.toPageX(350f), 0.01f)
        assertEquals(350f, vp.toViewX(vp.toPageX(350f)), 0.01f)
        assertTrue(vp.scrollsVertically)
    }

    @Test
    fun zoomKeepsFocusStill() {
        val z = vp.zoomBy(2f, 350f, 450f)
        assertEquals(2f, z.zoom, 0.001f)
        assertEquals(vp.toPageX(350f), z.toPageX(350f), 0.5f)
        assertEquals(vp.toPageY(450f), z.toPageY(450f), 0.5f)
    }

    @Test
    fun zoomAndPanAreClamped() {
        val z = vp.zoomBy(100f, 0f, 0f)
        assertEquals(Viewport.MAX_ZOOM, z.zoom, 0.001f)
        val far = z.panBy(-1e6f, -1e6f)
        assertEquals(1400 * far.scale - 700, far.panX, 0.5f)
        val back = z.panBy(1e6f, 1e6f)
        assertEquals(0f, back.panX, 0.01f)
        assertEquals(0f, back.panY, 0.01f)
        assertFalse(vp.zoomBy(0.1f, 0f, 0f).isZoomed)
    }

    @Test
    fun shortPagesAreTopAligned() {
        val landscape = Viewport(viewW = 700, viewH = 900, pageW = 1872, pageH = 1404).clamped()
        assertEquals(0f, landscape.panY, 0.001f)
        assertFalse(landscape.scrollsVertically)
    }
}
