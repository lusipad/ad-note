package com.adnote.export

import com.adnote.ink.StrokeGeometry
import com.adnote.model.Page
import com.adnote.ui.PageTemplateRenderer
import java.util.Locale

/** 把一页笔迹导出为 SVG。每条笔画是一个填充的 path，粗细与屏幕一致。支持底质模板与笔迹颜色导出。 */
object SvgExporter {

    fun export(page: Page): String = buildString {
        append("""<svg xmlns="http://www.w3.org/2000/svg" """)
        append("""viewBox="0 0 ${page.width} ${page.height}" width="${page.width}" height="${page.height}">""")
        append('\n')
        val bgFill = if (page.backgroundColor.isNotBlank()) page.backgroundColor.lowercase() else "#ffffff"
        append("""<rect width="100%" height="100%" fill="$bgFill"/>""").append('\n')

        val templateSvg = PageTemplateRenderer.generateSvgTemplate(page)
        if (templateSvg.isNotEmpty()) {
            append(templateSvg)
        }

        for (stroke in page.strokes) {
            val color = if (stroke.color.isNotBlank()) stroke.color.lowercase() else "#000000"
            val outline = StrokeGeometry.outline(stroke)
            if (outline.isEmpty()) {
                val p = stroke.points.firstOrNull() ?: continue
                val r = StrokeGeometry.widthAt(stroke.width, p.pressure) / 2f
                append("""<circle cx="${f(p.x)}" cy="${f(p.y)}" r="${f(r)}" fill="$color"/>""").append('\n')
                continue
            }
            append("""<path fill="$color" d="M""")
            outline.forEachIndexed { i, pt ->
                if (i > 0) append(" L")
                append(f(pt.x)).append(' ').append(f(pt.y))
            }
            append(""" Z"/>""").append('\n')
        }
        append("</svg>\n")
    }

    /** 保留一位小数，减小文件体积。 */
    private fun f(v: Float): String = String.format(Locale.ROOT, "%.1f", v)
}
