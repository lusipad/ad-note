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
