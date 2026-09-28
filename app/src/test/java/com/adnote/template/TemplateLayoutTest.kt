package com.adnote.template

import com.adnote.model.PageTemplate
import com.adnote.model.PaperPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateLayoutTest {

    private val w = 1404
    private val h = 1872

    @Test
    fun blankHasNoShapes() {
        assertTrue(TemplateLayout.build(PageTemplate.BLANK, "#FFFFFF", w, h).isEmpty())
        assertTrue(TemplateLayout.build(PageTemplate.GRID, "#FFFFFF", 0, 0).isEmpty())
    }

    @Test
    fun everyTemplateStaysInsideThePage() {
        for (t in PageTemplate.entries.filter { it != PageTemplate.BLANK }) {
            val shapes = TemplateLayout.build(t, "#FFFFFF", w, h)
            assertTrue("$t 应该有图元", shapes.isNotEmpty())
            for (s in shapes) {
                val xs: List<Float>
                val ys: List<Float>
                when (s) {
                    is TemplateLine -> { xs = listOf(s.x1, s.x2); ys = listOf(s.y1, s.y2) }
                    is TemplateRect -> { xs = listOf(s.left, s.right); ys = listOf(s.top, s.bottom) }
                    is TemplateDot -> { xs = listOf(s.cx); ys = listOf(s.cy) }
                }
                assertTrue("$t 越界: $s", xs.all { it in 0f..w.toFloat() } && ys.all { it in 0f..h.toFloat() })
            }
        }
    }

    @Test
    fun darkPaperUsesDarkLineColors() {
        val light = TemplateLayout.build(PageTemplate.GRID, PaperPresets.WHITE.hex, w, h)
        val dark = TemplateLayout.build(PageTemplate.GRID, PaperPresets.BLACKBOARD.hex, w, h)
        assertEquals(light.size, dark.size)
        assertTrue((light.first() as TemplateLine).color != (dark.first() as TemplateLine).color)
    }

    @Test
    fun engineeringPaperHasMajorLinesEveryFiveCells() {
        val lines = TemplateLayout.build(PageTemplate.ENGINEERING, "#FFFFFF", w, h).filterIsInstance<TemplateLine>()
        val major = lines.filter { it.width > 1f }
        val minor = lines.filter { it.width < 1f }
        assertTrue(major.isNotEmpty() && minor.isNotEmpty())
        // 每 5 条线 1 条主线（两个方向合计，允许末尾不足 5 条）
        assertEquals(lines.size / 5.0, major.size.toDouble(), 2.0)
        // 主线画在细线之后
        assertTrue(lines.indexOf(major.first()) > lines.indexOf(minor.last()))
    }

    @Test
    fun manuscriptRowsHaveTwentyCells() {
        val shapes = TemplateLayout.build(PageTemplate.MANUSCRIPT, "#FFFFFF", w, h)
        val rows = shapes.filterIsInstance<TemplateRect>()
        val dividers = shapes.filterIsInstance<TemplateLine>()
        assertTrue(rows.isNotEmpty())
        assertEquals(rows.size * 19, dividers.size)
    }

    @Test
    fun isometricRowsAreStaggered() {
        val dots = TemplateLayout.build(PageTemplate.ISOMETRIC, "#FFFFFF", w, h).filterIsInstance<TemplateDot>()
        val rows = dots.groupBy { it.cy }.toSortedMap().values.toList()
        assertTrue(rows.size > 2)
        assertTrue(rows[0].first().cx != rows[1].first().cx)
        assertEquals(rows[0].first().cx, rows[2].first().cx, 0.01f)
    }

    @Test
    fun layoutScalesWithPageWidth() {
        val full = TemplateLayout.build(PageTemplate.RULED, "#FFFFFF", 1404, 1872).filterIsInstance<TemplateLine>()
        val half = TemplateLayout.build(PageTemplate.RULED, "#FFFFFF", 702, 936).filterIsInstance<TemplateLine>()
        assertEquals(full.size, half.size)
        assertEquals(full[2].y1 / 2f, half[2].y1, 0.01f)
    }
}
