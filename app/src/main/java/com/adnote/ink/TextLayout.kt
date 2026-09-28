package com.adnote.ink

import com.adnote.model.Page
import com.adnote.model.TextBox

/**
 * 文字框排版：按宽度自动换行。屏幕上传入 Paint.measureText，SVG 导出与选区计算用估算宽度，
 * 两者使用同一套断行规则。
 */
object TextLayout {

    const val LINE_HEIGHT = 1.35f
    private const val RIGHT_MARGIN = 24f

    /** 估算字宽：中日韩等全角字符 1em，半角 0.55em，空格 0.3em。 */
    fun estimateWidth(s: String, size: Float): Float = s.sumOf { c ->
        when {
            c == ' ' -> 0.3
            c.code < 0x2E80 -> 0.55
            else -> 1.0
        }
    }.toFloat() * size

    /** 文字框实际可用宽度：显式宽度，或到页面右边距为止。 */
    fun availableWidth(box: TextBox, pageWidth: Int): Float {
        val toEdge = pageWidth - box.x - RIGHT_MARGIN
        return if (box.maxWidth > 0f) minOf(box.maxWidth, toEdge) else toEdge
    }

    /** 按 [maxWidth] 断行；保留用户输入的换行。 */
    fun wrap(text: String, maxWidth: Float, measure: (String) -> Float): List<String> {
        val out = ArrayList<String>()
        for (para in text.split('\n')) {
            if (para.isEmpty()) { out += ""; continue }
            var line = StringBuilder()
            for (ch in para) {
                val candidate = line.toString() + ch
                if (line.isNotEmpty() && measure(candidate) > maxWidth) {
                    // 西文尽量在空格处断开
                    val lastSpace = line.lastIndexOf(" ")
                    if (ch != ' ' && lastSpace > 0 && line.substring(lastSpace + 1).all { it.code < 0x2E80 }) {
                        out += line.substring(0, lastSpace)
                        line = StringBuilder(line.substring(lastSpace + 1))
                    } else {
                        out += line.toString()
                        line = StringBuilder()
                    }
                    if (ch == ' ') continue
                }
                line.append(ch)
            }
            out += line.toString()
        }
        return out
    }

    fun lines(box: TextBox, pageWidth: Int, measure: (String) -> Float = { estimateWidth(it, box.size) }): List<String> =
        wrap(box.text, availableWidth(box, pageWidth).coerceAtLeast(box.size), measure)

    /** 文字框包围盒 [left, top, right, bottom]。 */
    fun bounds(box: TextBox, pageWidth: Int, measure: (String) -> Float = { estimateWidth(it, box.size) }): FloatArray {
        val lines = lines(box, pageWidth, measure)
        val w = lines.maxOfOrNull { measure(it) } ?: 0f
        val h = lines.size * box.size * LINE_HEIGHT
        return floatArrayOf(box.x, box.y, box.x + maxOf(w, box.size), box.y + h)
    }

    /** 页面上 (x, y) 处的文字框（后绘制的优先）。 */
    fun hit(page: Page, x: Float, y: Float, slop: Float = 12f): TextBox? =
        page.texts.lastOrNull { t ->
            val b = bounds(t, page.width)
            x in b[0] - slop..b[2] + slop && y in b[1] - slop..b[3] + slop
        }
}
