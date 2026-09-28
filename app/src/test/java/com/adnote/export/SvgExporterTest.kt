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

    @Test
    fun testExportSvgWithSpecialTemplates() {
        val tianziPage = Page(
            width = 1404,
            height = 1872,
            template = com.adnote.model.PageTemplate.TIANZI,
            backgroundColor = "#FFFFFF"
        )
        val tianziSvg = SvgExporter.export(tianziPage)
        assertTrue(tianziSvg.contains("""<g id="template_tianzi""""))
        assertTrue(tianziSvg.contains("""stroke-dasharray="5,4""""))

        val pinyinPage = Page(
            width = 1404,
            height = 1872,
            template = com.adnote.model.PageTemplate.PINYIN,
            backgroundColor = "#FFFFFF"
        )
        val pinyinSvg = SvgExporter.export(pinyinPage)
        assertTrue(pinyinSvg.contains("""<g id="template_pinyin""""))
        assertTrue(pinyinSvg.contains("""stroke-dasharray="5,4""""))

        val musicPage = Page(
            width = 1404,
            height = 1872,
            template = com.adnote.model.PageTemplate.MUSIC,
            backgroundColor = "#FFFFFF"
        )
        val musicSvg = SvgExporter.export(musicPage)
        assertTrue(musicSvg.contains("""<g id="template_music""""))
    }

    @Test
    fun testExportHighlighterAndPencil() {
        val page = Page(
            width = 800,
            height = 1200,
            strokes = listOf(
                Stroke(
                    id = "hl",
                    points = listOf(InkPoint(10f, 10f), InkPoint(200f, 10f)),
                    width = 18f,
                    color = "#FACC15",
                    pen = com.adnote.model.PenType.HIGHLIGHTER
                ),
                Stroke(
                    id = "pencil",
                    points = listOf(InkPoint(10f, 50f), InkPoint(200f, 60f)),
                    width = 3f,
                    pen = com.adnote.model.PenType.PENCIL
                )
            )
        )
        val svg = SvgExporter.export(page)
        // 荧光笔：描边折线 + 半透明
        assertTrue(svg.contains("""<path fill="none" stroke="#facc15" stroke-width="18.0" stroke-linecap="round" stroke-linejoin="round" opacity="0.35" d="M10.0 10.0 L200.0 10.0"/>"""))
        // 铅笔：填充轮廓 + 半透明
        assertTrue(svg.contains("""<path fill="#000000" opacity="0.72" d="M"""))
    }

    @Test
    fun testExportNewTemplates() {
        for (t in listOf(
            com.adnote.model.PageTemplate.TODO,
            com.adnote.model.PageTemplate.ENGINEERING,
            com.adnote.model.PageTemplate.ISOMETRIC,
            com.adnote.model.PageTemplate.MANUSCRIPT
        )) {
            val svg = SvgExporter.export(Page(width = 1404, height = 1872, template = t))
            assertTrue(svg.contains("""<g id="template_${t.name.lowercase()}""""))
        }
        val blank = SvgExporter.export(Page(width = 1404, height = 1872))
        assertTrue(!blank.contains("<g id=\"template_"))
    }

    @Test
    fun testExportTextImagesLayersAndBackground() {
        val page = Page(
            width = 1000,
            height = 1400,
            backgroundImage = "bg/paper.png",
            layers = listOf(com.adnote.model.Layer(0, "底"), com.adnote.model.Layer(1, "隐藏", visible = false)),
            strokes = listOf(
                Stroke(id = "visible", points = listOf(InkPoint(1f, 1f), InkPoint(9f, 9f)), color = "#123456"),
                Stroke(id = "hidden", points = listOf(InkPoint(1f, 1f), InkPoint(9f, 9f)), color = "#abcdef", layer = 1),
            ),
            texts = listOf(com.adnote.model.TextBox(x = 10f, y = 20f, text = "a<b & 你好", size = 40f, linkPageId = "p2")),
            images = listOf(com.adnote.model.ImageItem(path = "images/x.jpg", x = 5f, y = 6f, width = 100f, height = 50f)),
        )
        val svg = SvgExporter.export(page) { path -> if (path == "images/x.jpg" || path == "bg/paper.png") byteArrayOf(1, 2, 3) else null }
        assertTrue(svg.contains("#123456"))
        assertTrue(!svg.contains("#abcdef"))
        assertTrue(svg.contains("a&lt;b &amp; 你好"))
        assertTrue(svg.contains("""text-decoration="underline""""))
        assertTrue(svg.contains("""<image x="5.0" y="6.0" width="100.0" height="50.0" preserveAspectRatio="none" href="data:image/jpeg;base64,AQID"/>"""))
        assertTrue(svg.contains("""href="data:image/png;base64,AQID""""))
        // 背景图在底纹前，笔迹在图片后
        assertTrue(svg.indexOf("data:image/png") < svg.indexOf("#123456"))
        assertTrue(svg.indexOf("data:image/jpeg") < svg.indexOf("#123456"))

        // 读不到图片时跳过而不是输出坏链接
        assertTrue(!SvgExporter.export(page).contains("<image"))
    }
}
