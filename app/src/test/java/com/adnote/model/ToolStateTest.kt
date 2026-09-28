package com.adnote.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ToolStateTest {

    @Test
    fun jsonRoundTrip() {
        val s = ToolState(
            tool = Tool.ERASER,
            penType = PenType.BRUSH,
            penColor = "#DC2626",
            penWidth = 7.5f,
            eraserMode = EraserMode.STROKE,
            eraserSize = EraserSize.LARGE,
        )
        assertEquals(s, ToolState.fromJson(ToolState.toJson(s)))
    }

    @Test
    fun invalidJsonFallsBackToDefaults() {
        assertEquals(ToolState(), ToolState.fromJson(null))
        assertEquals(ToolState(), ToolState.fromJson("{not json"))
        assertEquals(ToolState(), ToolState.fromJson("""{"tool":"UNKNOWN_TOOL"}"""))
    }

    @Test
    fun highlighterToolProducesHighlighterStrokes() {
        val s = ToolState(tool = Tool.HIGHLIGHTER, highlighterColor = "#FACC15", highlighterWidth = 20f)
        val stroke = s.newStroke(listOf(InkPoint(0f, 0f)))
        assertEquals(PenType.HIGHLIGHTER, stroke.pen)
        assertEquals("#FACC15", stroke.color)
        assertEquals(20f, stroke.width, 0f)

        val pen = s.copy(tool = Tool.PEN, penType = PenType.PENCIL).newStroke(listOf(InkPoint(0f, 0f)))
        assertEquals(PenType.PENCIL, pen.pen)
    }

    @Test
    fun withPenColorMaintainsRecentList() {
        var s = ToolState(recentPenColors = listOf("#1", "#2", "#3", "#4", "#5"))
        s = s.withPenColor("#3")
        assertEquals("#3", s.penColor)
        assertEquals(listOf("#3", "#1", "#2", "#4", "#5"), s.recentPenColors)
        s = s.withPenColor("#9")
        assertEquals(listOf("#9", "#3", "#1", "#2", "#4"), s.recentPenColors)
    }

    @Test
    fun adaptToPaperSwapsBlackAndWhiteInk() {
        val black = ToolState(penColor = PenPresets.BLACK.hex)
        assertEquals(PenPresets.WHITE.hex, black.adaptToPaper(isDarkPaper = true).penColor)
        assertSame(black, black.adaptToPaper(isDarkPaper = false))
        val white = ToolState(penColor = PenPresets.WHITE.hex)
        assertEquals(PenPresets.BLACK.hex, white.adaptToPaper(isDarkPaper = false).penColor)
        val blue = ToolState(penColor = PenPresets.BLUE.hex)
        assertSame(blue, blue.adaptToPaper(isDarkPaper = true))
    }

    @Test
    fun legacyStrokesWithoutPenFieldDecodeAsFountain() {
        val json = """{"id":"x","points":[{"x":1.0,"y":2.0}],"width":3.0,"color":"#000000"}"""
        val stroke = NoteJson.decodeFromString(Stroke.serializer(), json)
        assertEquals(PenType.FOUNTAIN, stroke.pen)
    }
}
