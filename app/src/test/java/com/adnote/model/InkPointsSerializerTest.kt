package com.adnote.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkPointsSerializerTest {

    private fun stroke(tilt: Float = 0f) = Stroke(
        id = "s1",
        points = (0 until 200).map { i ->
            InkPoint(x = 100.123f + i * 1.37f, y = 250.5f - i * 0.61f, pressure = 0.4567f, t = 1_727_512_345_678L + i * 7L, tilt = tilt)
        },
        width = 3.5f,
    )

    @Test
    fun roundTripKeepsPointsWithinPrecision() {
        val s = stroke()
        val back = NoteJson.decodeFromString(Stroke.serializer(), NoteJson.encodeToString(Stroke.serializer(), s))
        assertEquals(s.points.size, back.points.size)
        s.points.zip(back.points).forEach { (a, b) ->
            assertEquals(a.x, b.x, 0.006f)
            assertEquals(a.y, b.y, 0.006f)
            assertEquals(a.pressure, b.pressure, 0.0006f)
            assertEquals(a.t, b.t)
            assertEquals(0f, b.tilt, 0f)
        }
    }

    @Test
    fun tiltIsKeptWhenPresent() {
        val back = NoteJson.decodeFromString(Stroke.serializer(), NoteJson.encodeToString(Stroke.serializer(), stroke(tilt = 0.7854f)))
        assertEquals(0.785f, back.points[10].tilt, 0.001f)
    }

    @Test
    fun compactFormatIsMuchSmallerThanLegacy() {
        val s = stroke()
        val compact = NoteJson.encodeToString(Stroke.serializer(), s).length
        val legacy = NoteJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(InkPoint.serializer()), s.points).length
        assertTrue("compact=$compact legacy=$legacy", compact * 2 < legacy)
    }

    @Test
    fun readsLegacyPointObjects() {
        val json = """{"id":"a","points":[{"x":1.5,"y":2.0,"pressure":0.3,"t":10},{"x":3.0,"y":4.0,"t":20,"tilt":0.5}],"width":2.0}"""
        val s = NoteJson.decodeFromString(Stroke.serializer(), json)
        assertEquals(listOf(InkPoint(1.5f, 2f, 0.3f, 10L), InkPoint(3f, 4f, 0.5f, 20L, 0.5f)), s.points)
    }

    @Test
    fun emptyAndSinglePointStrokes() {
        for (pts in listOf(emptyList(), listOf(InkPoint(5f, 6f, 0.5f, 99L)))) {
            val s = Stroke(id = "x", points = pts)
            assertEquals(pts, NoteJson.decodeFromString(Stroke.serializer(), NoteJson.encodeToString(Stroke.serializer(), s)).points)
        }
    }
}
