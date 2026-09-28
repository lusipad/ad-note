package com.adnote.export

import com.adnote.ink.StrokeGeometry
import com.adnote.ink.TextLayout
import com.adnote.model.ImageItem
import com.adnote.model.Page
import com.adnote.model.PageTemplate
import com.adnote.model.Stroke
import com.adnote.model.TextBox
import com.adnote.model.layerContents
import com.adnote.template.TemplateDot
import com.adnote.template.TemplateLayout
import com.adnote.template.TemplateLine
import com.adnote.template.TemplateRect
import java.util.Base64
import java.util.Locale

/**
 * 把一页笔迹导出为 SVG。支持底纹模板、纸张底色、笔迹颜色与笔型：
 * 压感笔（钢笔、毛笔等）导出为填充轮廓 path，等宽笔（马克笔、荧光笔）导出为描边 path，
 * 半透明笔型（铅笔、荧光笔）带 opacity。
 */
object SvgExporter {

    /**
     * @param assets 按相对笔记目录的路径读取图片字节；图片以 data URI 内嵌，
     *   这样 SVG 作为 Obsidian 里的 <img> 显示时也能看到图片。
     */
    fun export(page: Page, assets: (String) -> ByteArray? = { null }): String = buildString {
        append("""<svg xmlns="http://www.w3.org/2000/svg" """)
        append("""viewBox="0 0 ${page.width} ${page.height}" width="${page.width}" height="${page.height}">""")
        append('\n')
        val bgFill = if (page.backgroundColor.isNotBlank()) page.backgroundColor.lowercase() else "#ffffff"
        append("""<rect width="100%" height="100%" fill="$bgFill"/>""").append('\n')

        page.backgroundImage?.let { path ->
            dataUri(path, assets)?.let { uri ->
                append("""<image x="0" y="0" width="${page.width}" height="${page.height}" """)
                append("""preserveAspectRatio="xMidYMid slice" href="$uri"/>""").append('\n')
            }
        }

        append(templateSvg(page))

        for (content in page.layerContents()) {
            content.images.forEach { appendImage(it, assets) }
            val (highlighters, normalStrokes) = content.strokes.partition { it.pen == com.adnote.model.PenType.HIGHLIGHTER }
            highlighters.forEach { appendStroke(it) }
            normalStrokes.forEach { appendStroke(it) }
            content.texts.forEach { appendText(it, page.width) }
        }
        append("</svg>\n")
    }

    private fun StringBuilder.appendStroke(stroke: Stroke) {
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
            return
        }

        val outline = StrokeGeometry.outline(stroke)
        if (outline.isEmpty()) {
            val p = stroke.points.firstOrNull() ?: return
            val r = StrokeGeometry.dotRadius(stroke)
            append("""<circle cx="${f(p.x)}" cy="${f(p.y)}" r="${f(r)}" fill="$color"$opacity/>""").append('\n')
            return
        }
        append("""<path fill="$color"$opacity d="M""")
        outline.forEachIndexed { i, pt ->
            if (i > 0) append(" L")
            append(f(pt.x)).append(' ').append(f(pt.y))
        }
        append(""" Z"/>""").append('\n')
    }

    private fun StringBuilder.appendText(box: TextBox, pageWidth: Int) {
        val color = box.color.lowercase()
        val deco = if (box.linkPageId != null) """ text-decoration="underline"""" else ""
        append("""<text font-family="sans-serif" font-size="${f(box.size)}" fill="$color"$deco>""")
        TextLayout.lines(box, pageWidth).forEachIndexed { i, line ->
            val baseline = box.y + box.size * (i * TextLayout.LINE_HEIGHT + BASELINE)
            append("""<tspan x="${f(box.x)}" y="${f(baseline)}">""").append(escapeXml(line)).append("</tspan>")
        }
        append("</text>\n")
    }

    private fun StringBuilder.appendImage(img: ImageItem, assets: (String) -> ByteArray?) {
        val uri = dataUri(img.path, assets) ?: return
        append("""<image x="${f(img.x)}" y="${f(img.y)}" width="${f(img.width)}" height="${f(img.height)}" """)
        append("""preserveAspectRatio="none" href="$uri"/>""").append('\n')
    }

    private fun dataUri(path: String, assets: (String) -> ByteArray?): String? {
        val bytes = assets(path) ?: return null
        val mime = when (path.substringAfterLast('.').lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
        return "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)
    }

    internal fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** 第一行基线相对文字框顶部的位置（字号的倍数），屏幕渲染使用同一数值。 */
    const val BASELINE = 0.95f

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
