package com.adnote.export

import com.adnote.ink.StrokeGeometry
import com.adnote.model.Page
import com.adnote.model.PageTemplate
import com.adnote.template.TemplateDot
import com.adnote.template.TemplateLayout
import com.adnote.template.TemplateLine
import com.adnote.template.TemplateRect
import java.util.Locale

/**
 * 把一页笔迹导出为 SVG。支持底纹模板、纸张底色、笔迹颜色与笔型：
 * 压感笔（钢笔、毛笔等）导出为填充轮廓 path，等宽笔（马克笔、荧光笔）导出为描边 path，
 * 半透明笔型（铅笔、荧光笔）带 opacity。
 */
object SvgExporter {

    fun export(page: Page): String = buildString {
        append("""<svg xmlns="http://www.w3.org/2000/svg" """)
        append("""viewBox="0 0 ${page.width} ${page.height}" width="${page.width}" height="${page.height}">""")
        append('\n')
        val bgFill = if (page.backgroundColor.isNotBlank()) page.backgroundColor.lowercase() else "#ffffff"
        append("""<rect width="100%" height="100%" fill="$bgFill"/>""").append('\n')

        append(templateSvg(page))

        for (stroke in page.strokes) {
            val color = if (stroke.color.isNotBlank()) stroke.color.lowercase() else "#000000"
            val opacity = if (stroke.pen.opacity < 1f) """ opacity="${f2(stroke.pen.opacity)}"""" else ""

            if (stroke.pen.constantWidth && stroke.points.size > 1) {
                val line = StrokeGeometry.centerline(stroke)
                append("""<path fill="none" stroke="$color" stroke-width="${f(stroke.width)}" """)
                append("""stroke-linecap="round" stroke-linejoin="round"$opacity d="M""")
                line.forEachIndexed { i, pt ->
                    if (i > 0) append(" L")
                    append(f(pt.x)).append(' ').append(f(pt.y))
                }
                append("\"/>\n")
                continue
            }

            val outline = StrokeGeometry.outline(stroke)
            if (outline.isEmpty()) {
                val p = stroke.points.firstOrNull() ?: continue
                val r = StrokeGeometry.dotRadius(stroke)
                append("""<circle cx="${f(p.x)}" cy="${f(p.y)}" r="${f(r)}" fill="$color"$opacity/>""").append('\n')
                continue
            }
            append("""<path fill="$color"$opacity d="M""")
            outline.forEachIndexed { i, pt ->
                if (i > 0) append(" L")
                append(f(pt.x)).append(' ').append(f(pt.y))
            }
            append(""" Z"/>""").append('\n')
        }
        append("</svg>\n")
    }

    /** 底纹图元转为 SVG 片段；空白模板返回空串。 */
    fun templateSvg(page: Page): String {
        if (page.template == PageTemplate.BLANK) return ""
        val shapes = TemplateLayout.build(page.template, page.backgroundColor, page.width, page.height)
        val dash = TemplateLayout.dashIntervals(page.width).joinToString(",") { trim(it) }
        return buildString {
            append("""<!-- adnote template: ${page.template.name} -->""").append('\n')
            append("""<g id="template_${page.template.name.lowercase()}" opacity="0.85">""").append('\n')
            for (s in shapes) {
                when (s) {
                    is TemplateLine -> {
                        append("""  <line x1="${f(s.x1)}" y1="${f(s.y1)}" x2="${f(s.x2)}" y2="${f(s.y2)}" """)
                        append("""stroke="${s.color}" stroke-width="${trim(s.width)}"""")
                        if (s.dashed) append(""" stroke-dasharray="$dash"""")
                        append("/>\n")
                    }
                    is TemplateRect -> {
                        append("""  <rect x="${f(s.left)}" y="${f(s.top)}" width="${f(s.right - s.left)}" height="${f(s.bottom - s.top)}" """)
                        append("""stroke="${s.color}" stroke-width="${trim(s.width)}" fill="none"/>""").append('\n')
                    }
                    is TemplateDot -> {
                        append("""  <circle cx="${f(s.cx)}" cy="${f(s.cy)}" r="${f(s.r)}" fill="${s.color}"/>""").append('\n')
                    }
                }
            }
            append("</g>\n")
        }
    }

    /** 保留一位小数，减小文件体积。 */
    private fun f(v: Float): String = String.format(Locale.ROOT, "%.1f", v)

    private fun f2(v: Float): String = String.format(Locale.ROOT, "%.2f", v)

    /** 最多两位小数，并去掉末尾多余的 0（5.0 → 5）。 */
    private fun trim(v: Float): String =
        String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')
}
