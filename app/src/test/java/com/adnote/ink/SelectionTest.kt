package com.adnote.ink

import com.adnote.model.ImageItem
import com.adnote.model.InkPoint
import com.adnote.model.Page
import com.adnote.model.Stroke
import com.adnote.model.TextBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class SelectionTest {

    private val page = Page(
        width = 1000, height = 1400,
        strokes = listOf(
            Stroke(id = "s", points = listOf(InkPoint(100f, 100f), InkPoint(200f, 100f)), width = 4f),
            Stroke(id = "other-layer", points = listOf(InkPoint(120f, 120f), InkPoint(150f, 150f)), layer = 1),
        ),
        texts = listOf(TextBox(id = "t", x = 100f, y = 150f, text = "你好", size = 40f)),
        images = listOf(ImageItem(id = "i", path = "images/a.jpg", x = 600f, y = 600f, width = 200f, height = 100f)),
    )

    private val box = listOf(InkPoint(50f, 50f), InkPoint(400f, 50f), InkPoint(400f, 400f), InkPoint(50f, 400f))

    @Test
    fun lassoSelectsAcrossItemTypesOnActiveLayers() {
        val sel = SelectionOps.lasso(page, box, setOf(0))
        assertEquals(setOf("s"), sel.strokes)
        assertEquals(setOf("t"), sel.texts)
        assertTrue(sel.images.isEmpty())

        val all = SelectionOps.lasso(page, box, setOf(0, 1))
        assertTrue("other-layer" in all.strokes)
    }

    @Test
    fun scaleAroundPivot() {
        val sel = Selection(strokes = setOf("s"), images = setOf("i"))
        val out = SelectionOps.transform(page, sel, Affine.scale(2f, 100f, 100f))
        val s = out.strokes.first { it.id == "s" }
        assertEquals(300f, s.points[1].x, 0.01f)
        assertEquals(8f, s.width, 0.01f)
        val img = out.images.single()
        assertEquals(400f, img.width, 0.01f)
        // 图片中心 (700,650) → (1300,1200)
        assertEquals(1300f, img.x + img.width / 2f, 0.01f)
        // 未选中的笔画不变
        assertEquals(page.strokes[1], out.strokes[1])
    }

    @Test
    fun rotateStrokesKeepsTextHorizontal() {
        val sel = Selection(strokes = setOf("s"), texts = setOf("t"))
        val out = SelectionOps.transform(page, sel, Affine.rotate((PI / 2).toFloat(), 100f, 100f))
        val s = out.strokes.first { it.id == "s" }
        assertEquals(100f, s.points[1].x, 0.01f)
        assertEquals(200f, s.points[1].y, 0.01f)
        assertEquals(40f, out.texts.single().size, 0.01f)
    }

    @Test
    fun copyPasteAssignsNewIdsAndLayer() {
        val sel = Selection(strokes = setOf("s"), texts = setOf("t"), images = setOf("i"))
        val clip = SelectionOps.copy(page, sel, "note-a")
        val (pasted, newSel) = SelectionOps.paste(Page(width = 1000, height = 1400), clip, layer = 2, dx = 10f, dy = 0f) { "copied/$it" }
        assertEquals(3, newSel.size)
        assertNotEquals("s", pasted.strokes.single().id)
        assertEquals(2, pasted.strokes.single().layer)
        assertEquals(110f, pasted.strokes.single().points[0].x, 0.01f)
        assertEquals("copied/images/a.jpg", pasted.images.single().path)
    }

    @Test
    fun deleteAndBounds() {
        val sel = Selection(strokes = setOf("s"), images = setOf("i"))
        val b = SelectionOps.bounds(page, sel)!!
        assertEquals(96f, b[0], 0.01f)
        assertEquals(800f, b[2], 0.01f)
        val out = SelectionOps.delete(page, sel)
        assertEquals(listOf("other-layer"), out.strokes.map { it.id })
        assertTrue(out.images.isEmpty())
        assertEquals(1, out.texts.size)
    }

    @Test
    fun clampInsidePage() {
        assertEquals(10f to 0f, SelectionOps.clampInside(floatArrayOf(-10f, 5f, 90f, 50f), 1000, 1400))
        assertEquals(-20f to -30f, SelectionOps.clampInside(floatArrayOf(900f, 1000f, 1020f, 1430f), 1000, 1400))
    }
}
