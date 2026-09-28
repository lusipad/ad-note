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
            eraserRadius = 30f,
        )
        assertEquals(s, ToolState.fromJson(ToolState.toJson(s)))
    }

    @Test
    fun oldEraserSizeFieldIsIgnored() {
        // 旧版保存的是档位名称，现在改为连续半径：旧字段忽略，其余设置照常恢复
        val s = ToolState.fromJson("""{"tool":"ERASER","eraserSize":"LARGE","penColor":"#DC2626"}""")
        assertEquals(Tool.ERASER, s.tool)
        assertEquals("#DC2626", s.penColor)
        assertEquals(EraserSizes.DEFAULT, s.eraserRadius, 0f)
    }

    @Test
    fun eraserRadiusIsClamped() {
        assertEquals(EraserSizes.MIN, EraserSizes.clamp(0f), 0f)
        assertEquals(EraserSizes.MAX, EraserSizes.clamp(1000f), 0f)
        assertEquals(20f, EraserSizes.clamp(20f), 0f)
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

    @Test
    fun penPresetsSwitchingAndSaving() {
        var s = ToolState()
        assertEquals(3, s.presets.size)
        // Default matches slot 0: FOUNTAIN, BLACK, 2.0f
        s = s.copy(tool = Tool.PEN, penType = PenType.FOUNTAIN, penColor = PenPresets.BLACK.hex, penWidth = PenPresets.WIDTH_FINE)
        assertEquals(0, s.activePresetIndex())

        // Apply slot 1 (red ballpoint)
        s = s.applyPreset(s.presets[1])
        assertEquals(Tool.PEN, s.tool)
        assertEquals(PenType.BALLPOINT, s.penType)
        assertEquals(PenPresets.RED.hex, s.penColor)
        assertEquals(1, s.activePresetIndex())

        // Apply slot 2 (highlighter)
        s = s.applyPreset(s.presets[2])
        assertEquals(Tool.HIGHLIGHTER, s.tool)
        assertEquals(2, s.activePresetIndex())

        // Modify pen setting, no preset matches
        s = s.copy(highlighterColor = "#000000")
        assertEquals(null, s.activePresetIndex())

        // Save custom setting into slot 2
        s = s.savePreset(2)
        assertEquals(2, s.activePresetIndex())
        assertEquals("#000000", s.presets[2].color)
    }

    @Test
    fun dockPositionDefaultsToTopAndPersists() {
        val s = ToolState(dockPosition = DockPosition.LEFT)
        val json = ToolState.toJson(s)
        val loaded = ToolState.fromJson(json)
        assertEquals(DockPosition.LEFT, loaded.dockPosition)
    }
}

class ModelCompatTest {
    @Test
    fun newFieldsHaveCompactDefaultsAndOldJsonStillLoads() {
        val json = NoteJson.encodeToString(Stroke.serializer(), Stroke(id = "a", points = listOf(InkPoint(1f, 2f))))
        assert(!json.contains("tilt")) { json }
        assert(!json.contains("layer")) { json }

        val legacyPage = """{"id":"p","width":10,"height":20,"strokes":[]}"""
        val page = NoteJson.decodeFromString(Page.serializer(), legacyPage)
        assertEquals(listOf(Layer.DEFAULT), page.layers)
        assertEquals(emptyList<TextBox>(), page.texts)

        val tilted = InkPoint(1f, 1f, tilt = 0.7f)
        assertEquals(tilted, NoteJson.decodeFromString(InkPoint.serializer(), NoteJson.encodeToString(InkPoint.serializer(), tilted)))
    }
}
