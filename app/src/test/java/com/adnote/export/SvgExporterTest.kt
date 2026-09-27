package com.adnote.export

import com.adnote.model.InkPoint
import com.adnote.model.Page
import com.adnote.model.Stroke
import org.junit.Assert.assertTrue
import org.junit.Test

class SvgExporterTest {

    @Test
    fun testExportSvg() {
        val page = Page(
            width = 800,
            height = 1200,
            strokes = listOf(
                Stroke(
                    id = "s1",
                    points = listOf(InkPoint(10f, 10f), InkPoint(50f, 50f)),
                    width = 2f
                ),
                Stroke(
                    id = "dot",
                    points = listOf(InkPoint(100f, 100f)),
                    width = 4f
                )
            )
        )

        val svg = SvgExporter.export(page)
        assertTrue(svg.contains("""viewBox="0 0 800 1200""""))
        assertTrue(svg.contains("""width="800" height="1200""""))
        assertTrue(svg.contains("""<rect width="100%" height="100%" fill="#ffffff"/>"""))
        assertTrue(svg.contains("<path fill=\"#000000\" d=\"M"))
        assertTrue(svg.contains("<circle cx=\"100.0\" cy=\"100.0\""))
    }

    @Test
    fun testExportSvgWithTemplateAndColor() {
        val page = Page(
            width = 800,
            height = 1200,
            template = com.adnote.model.PageTemplate.RULED,
            backgroundColor = "#FBF8F1",
            strokes = listOf(
                Stroke(
                    id = "s_blue",
                    points = listOf(InkPoint(10f, 10f), InkPoint(30f, 30f)),
                    width = 3.5f,
                    color = "#1E3A8A"
                )
            )
        )

        val svg = SvgExporter.export(page)
        assertTrue(svg.contains("""fill="#fbf8f1""""))
        assertTrue(svg.contains("""<g id="template_ruled""""))
        assertTrue(svg.contains("""fill="#1e3a8a""""))
    }
}
