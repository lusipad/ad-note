package com.adnote.ink

import com.adnote.model.InkPoint
import com.adnote.model.PenType
import com.adnote.model.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineGrouperTest {

    /** 一个矩形范围内的两点笔画。 */
    private fun stroke(id: String, left: Float, top: Float, right: Float, bottom: Float, pen: PenType = PenType.FOUNTAIN) =
        Stroke(id = id, points = listOf(InkPoint(left, top), InkPoint(right, bottom)), pen = pen)

    @Test
    fun twoRowsSplitIntoTwoLines() {
        // 两行各三个字，交错书写
        val strokes = listOf(
            stroke("a1", 10f, 100f, 40f, 140f),
            stroke("b1", 10f, 200f, 40f, 240f),
            stroke("a2", 50f, 105f, 80f, 138f),
            stroke("b2", 50f, 202f, 80f, 241f),
            stroke("a3", 90f, 100f, 120f, 140f),
            stroke("b3", 90f, 200f, 120f, 240f),
        )
        val lines = LineGrouper.group(strokes)
        assertEquals(2, lines.size)
        assertEquals(listOf("a1", "a2", "a3"), lines[0].map { it.id })
        assertEquals(listOf("b1", "b2", "b3"), lines[1].map { it.id })
    }

    @Test
    fun linesSortedTopToBottom() {
        val strokes = listOf(
            stroke("bottom", 0f, 300f, 30f, 340f),
            stroke("top", 0f, 100f, 30f, 140f),
        )
        assertEquals(listOf("top", "bottom"), LineGrouper.group(strokes).map { it[0].id })
    }

    @Test
    fun dotJoinsNearestLine() {
        val dot = Stroke(id = "dot", points = listOf(InkPoint(45f, 130f)))
        val strokes = listOf(
            stroke("a", 10f, 100f, 40f, 140f),
            stroke("b", 10f, 200f, 40f, 240f),
            dot,
        )
        val lines = LineGrouper.group(strokes)
        assertEquals(2, lines.size)
        assertEquals(listOf("a", "dot"), lines[0].map { it.id })
    }

    @Test
    fun highlighterIgnored() {
        val strokes = listOf(
            stroke("h", 0f, 100f, 200f, 140f, pen = PenType.HIGHLIGHTER),
            stroke("a", 10f, 100f, 40f, 140f),
        )
        val lines = LineGrouper.group(strokes)
        assertEquals(1, lines.size)
        assertEquals(listOf("a"), lines[0].map { it.id })
        assertTrue(LineGrouper.group(listOf(stroke("h2", 0f, 0f, 1f, 1f, pen = PenType.HIGHLIGHTER))).isEmpty())
    }

    @Test
    fun emptyInputGivesEmptyOutput() {
        assertTrue(LineGrouper.group(emptyList()).isEmpty())
        assertTrue(LineGrouper.group(listOf(Stroke(points = emptyList()))).isEmpty())
    }

    @Test
    fun descenderStillSameLine() {
        // 第二笔比第一笔低一截（像 g 的下伸部分），重叠仍然超过一半
        val strokes = listOf(
            stroke("a", 10f, 100f, 40f, 140f),
            stroke("g", 50f, 110f, 80f, 165f),
        )
        assertEquals(1, LineGrouper.group(strokes).size)
    }
}
