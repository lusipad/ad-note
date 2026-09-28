package com.adnote.ink

import com.adnote.model.InkPoint
import com.adnote.model.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeHistoryTest {

    private fun stroke(id: String) = Stroke(id = id, points = listOf(InkPoint(0f, 0f)))

    @Test
    fun undoRedoRoundTrip() {
        val h = StrokeHistory()
        val empty = emptyList<Stroke>()
        val one = listOf(stroke("a"))
        val two = one + stroke("b")

        h.record(empty)
        h.record(one)
        assertTrue(h.canUndo)
        assertFalse(h.canRedo)

        assertEquals(one, h.undo(two))
        assertEquals(empty, h.undo(one))
        assertNull(h.undo(empty))
        assertTrue(h.canRedo)

        assertEquals(one, h.redo(empty))
        assertEquals(two, h.redo(one))
        assertNull(h.redo(two))
    }

    @Test
    fun newActionClearsRedo() {
        val h = StrokeHistory()
        h.record(emptyList())
        h.undo(listOf(stroke("a")))
        assertTrue(h.canRedo)

        h.record(emptyList())
        assertFalse(h.canRedo)
    }

    @Test
    fun limitDropsOldestEntries() {
        val h = StrokeHistory(limit = 3)
        repeat(5) { h.record(listOf(stroke("s$it"))) }
        var steps = 0
        var cur = emptyList<Stroke>()
        while (true) {
            cur = h.undo(cur) ?: break
            steps++
        }
        assertEquals(3, steps)
        assertEquals("s2", cur.single().id)
    }
}
