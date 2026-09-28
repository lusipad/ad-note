package com.adnote.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PageOpsTest {

    private fun page(tag: String, template: PageTemplate = PageTemplate.BLANK) = Page(
        id = tag,
        width = 1404,
        height = 1872,
        template = template,
        backgroundColor = "#FBF8F1",
        strokes = listOf(Stroke(id = "$tag-s", points = listOf(InkPoint(1f, 1f)))),
    )

    private fun note(vararg tags: String, pdf: Boolean = false) = Note(
        title = "t",
        pages = tags.map { page(it) },
        createdAt = 0,
        updatedAt = 0,
        pdfPath = if (pdf) "document.pdf" else null,
    )

    @Test
    fun insertBlankAfterInheritsPaper() {
        val n = note("a", "b").let { it.copy(pages = it.pages.map { p -> p.copy(template = PageTemplate.GRID) }) }
        val out = PageOps.insertBlankAfter(n, 0, now = 5)
        assertEquals(3, out.pages.size)
        assertEquals(listOf("a", "b"), listOf(out.pages[0].id, out.pages[2].id))
        val inserted = out.pages[1]
        assertTrue(inserted.strokes.isEmpty())
        assertEquals(PageTemplate.GRID, inserted.template)
        assertEquals("#FBF8F1", inserted.backgroundColor)
        assertEquals(5L, out.updatedAt)
    }

    @Test
    fun duplicateCopiesStrokesWithNewIds() {
        val out = PageOps.duplicate(note("a"), 0)
        assertEquals(2, out.pages.size)
        val (orig, copy) = out.pages
        assertNotEquals(orig.id, copy.id)
        assertEquals(orig.strokes.map { it.points }, copy.strokes.map { it.points })
        assertNotEquals(orig.strokes[0].id, copy.strokes[0].id)
    }

    @Test
    fun deleteKeepsAtLeastOnePage() {
        val n = note("a", "b", "c")
        assertEquals(listOf("a", "c"), PageOps.delete(n, 1).pages.map { it.id })
        val single = note("a")
        assertSame(single, PageOps.delete(single, 0))
    }

    @Test
    fun moveReordersPages() {
        val n = note("a", "b", "c")
        assertEquals(listOf("b", "c", "a"), PageOps.move(n, 0, 2).pages.map { it.id })
        assertEquals(listOf("c", "a", "b"), PageOps.move(n, 2, 0).pages.map { it.id })
        assertSame(n, PageOps.move(n, 1, 1))
        assertSame(n, PageOps.move(n, 0, 5))
    }

    @Test
    fun clearStrokes() {
        val n = note("a", "b")
        val out = PageOps.clearStrokes(n, 1)
        assertTrue(out.pages[1].strokes.isEmpty())
        assertEquals(1, out.pages[0].strokes.size)
        assertSame(out, PageOps.clearStrokes(out, 1))
    }

    @Test
    fun pdfNotesKeepTheirStructure() {
        val n = note("a", "b", pdf = true)
        assertSame(n, PageOps.insertBlankAfter(n, 0))
        assertSame(n, PageOps.duplicate(n, 0))
        assertSame(n, PageOps.delete(n, 0))
        assertSame(n, PageOps.move(n, 0, 1))
        // 清空笔迹仍然允许
        assertTrue(PageOps.clearStrokes(n, 0).pages[0].strokes.isEmpty())
    }
}

class PageOpsLayoutTest {
    private val note = Note(
        title = "t",
        pages = listOf(Page(id = "p", width = 1000, height = 1400)),
        createdAt = 0, updatedAt = 0,
    )

    @Test
    fun orientationSwapsDimensions() {
        val p = PageOps.toggleOrientation(note, 0).pages[0]
        assertEquals(1400, p.width)
        assertEquals(1000, p.height)
    }

    @Test
    fun extendDownIsCapped() {
        var n = note
        repeat(20) { n = PageOps.extendDown(n, 0) }
        assertEquals((1000 * PageOps.MAX_ASPECT).toInt(), n.pages[0].height)
        assertSame(n, PageOps.extendDown(n, 0))
    }

    @Test
    fun bookmarks() {
        val b = PageOps.setBookmark(note, 0, "  第一章 ")
        assertEquals("第一章", b.pages[0].bookmark)
        assertEquals(null, PageOps.setBookmark(b, 0, " ").pages[0].bookmark)
        assertTrue(b.searchableText().contains("第一章"))
    }
}

class LegacyCoordsTest {
    private fun note(pdf: Boolean, version: Int = 0) = Note(
        title = "t",
        pages = listOf(Page(width = 1190, height = 1684, strokes = listOf(
            Stroke(id = "s", points = listOf(InkPoint(1404f, 1500f)), width = 4f),
        ))),
        createdAt = 0, updatedAt = 0,
        pdfPath = if (pdf) "document.pdf" else null,
        coordVersion = version,
    )

    @Test
    fun pdfPagesScaleEachAxis() {
        val out = LegacyCoords.migrate(note(pdf = true), viewW = 1404, viewH = 1500)
        val p = out.pages[0].strokes[0].points[0]
        assertEquals(1190f, p.x, 0.01f)
        assertEquals(1684f, p.y, 0.01f)
        assertEquals(LegacyCoords.CURRENT, out.coordVersion)
    }

    @Test
    fun notebookPagesScaleUniformly() {
        val out = LegacyCoords.migrate(note(pdf = false), viewW = 1404, viewH = 1500)
        val s = out.pages[0].strokes[0]
        assertEquals(1190f, s.points[0].x, 0.01f)
        assertEquals(1500f * 1190f / 1404f, s.points[0].y, 0.01f)
        assertEquals(4f * 1190f / 1404f, s.width, 0.01f)
    }

    @Test
    fun migratedNotesAreLeftAlone() {
        val n = note(pdf = true, version = LegacyCoords.CURRENT)
        assertSame(n, LegacyCoords.migrate(n, 100, 100))
    }

    @Test
    fun newNotesStartAtCurrentVersion() {
        val tmp = kotlin.io.path.createTempDirectory().toFile()
        val repo = com.adnote.storage.NoteRepository(tmp)
        assertEquals(LegacyCoords.CURRENT, repo.create("n", "f", 10, 10).coordVersion)
        tmp.deleteRecursively()
    }
}

class FitEmptyPageTest {
    private fun note(page: Page, pdf: Boolean = false) = Note(
        title = "t", pages = listOf(page), createdAt = 0, updatedAt = 0,
        pdfPath = if (pdf) "document.pdf" else null,
    )

    @Test
    fun emptyStandardPageAdoptsCanvasAspect() {
        val out = PageOps.fitEmptyPageToCanvas(note(Page(width = 1404, height = 1872)), 0, 1404, 1650)
        assertEquals(1404, out.pages[0].width)
        assertEquals(1650, out.pages[0].height)
        // 调整后整页刚好铺满画布：不留灰边、不需要滚动
        val vp = Viewport(1404, 1650, out.pages[0].width, out.pages[0].height).clamped()
        assertEquals(1f, vp.scale, 0.001f)
        assertEquals(0f, vp.panX, 0.01f)
    }

    @Test
    fun pagesWithContentKeepTheirSize() {
        val withInk = Page(width = 1404, height = 1872, strokes = listOf(Stroke(points = listOf(InkPoint(1f, 1f)))))
        val n = note(withInk)
        assertSame(n, PageOps.fitEmptyPageToCanvas(n, 0, 1404, 1650))
        val withText = note(Page(width = 1404, height = 1872, texts = listOf(TextBox(x = 0f, y = 0f, text = "a"))))
        assertSame(withText, PageOps.fitEmptyPageToCanvas(withText, 0, 1404, 1650))
    }

    @Test
    fun pdfLandscapeAndLongPagesAreLeftAlone() {
        val pdf = note(Page(width = 1190, height = 1684), pdf = true)
        assertSame(pdf, PageOps.fitEmptyPageToCanvas(pdf, 0, 1404, 1650))
        // 页面被切成横向，而画布是竖向：保持用户选的方向
        val landscape = note(Page(width = 1872, height = 1404))
        assertSame(landscape, PageOps.fitEmptyPageToCanvas(landscape, 0, 1404, 1650))
        // 向下延长过的长页
        val long = note(Page(width = 1404, height = 1872 * 2))
        assertSame(long, PageOps.fitEmptyPageToCanvas(long, 0, 1404, 1650))
        // 已经匹配时不改
        val same = note(Page(width = 1404, height = 1651))
        assertSame(same, PageOps.fitEmptyPageToCanvas(same, 0, 1404, 1650))
    }
}
