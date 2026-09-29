package com.adnote.ink

import com.adnote.model.PenType
import com.adnote.model.Stroke
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 把一页笔迹按行分组，供手写识别逐行处理。ML Kit 对单行文本效果最好，整页一起送会乱序。
 */
object LineGrouper {

    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val height: Float get() = bottom - top
    }

    fun boundsOf(stroke: Stroke): Bounds {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (p in stroke.points) {
            if (p.x < l) l = p.x
            if (p.x > r) r = p.x
            if (p.y < t) t = p.y
            if (p.y > b) b = p.y
        }
        return Bounds(l, t, r, b)
    }

    /**
     * 每一笔按书写顺序找「垂直重叠比例」最大的已有行，比例 ≥ [minOverlap] 就归入，否则另起一行。
     * 重叠比例 = 重叠高度 / 两者中较矮者的高度。
     * 笔画高度至少按所有笔画高度中位数的 [minHeightRatio] 计，避免一个点、一条横线这种很扁的笔画归不进任何行。
     * 荧光笔不参与。返回的行按顶部从上到下排序，行内保持书写顺序。
     */
    fun group(strokes: List<Stroke>, minOverlap: Float = 0.4f, minHeightRatio: Float = 0.3f): List<List<Stroke>> {
        val items = strokes
            .filter { it.points.isNotEmpty() && it.pen != PenType.HIGHLIGHTER }
            .map { it to boundsOf(it) }
        if (items.isEmpty()) return emptyList()

        val sortedHeights = items.map { it.second.height }.sorted()
        val medianHeight = sortedHeights[sortedHeights.size / 2]
        val minHeight = max(medianHeight * minHeightRatio, 1f)

        class Line(var top: Float, var bottom: Float, val strokes: MutableList<Pair<Int, Stroke>>, var flat: Boolean)

        // 竖框、大括号、表格边线这类很高的笔画不参与分行，否则会把它跨过的所有行撑成一行。
        val tallLimit = medianHeight * 2f
        val tall = ArrayList<Pair<Int, Stroke>>()
        val lines = ArrayList<Line>()
        for ((index, item) in items.withIndex()) {
            val (stroke, b) = item
            if (b.height > tallLimit) {
                tall += index to stroke
                continue
            }
            val isFlat = b.height < minHeight
            val pad = max(0f, (minHeight - b.height) / 2f)
            val top = b.top - pad
            val bottom = b.bottom + pad
            var best: Line? = null
            var bestRatio = 0f
            for (line in lines) {
                val overlap = min(bottom, line.bottom) - max(top, line.top)
                if (overlap <= 0f) continue
                val ratio = overlap / max(min(bottom - top, line.bottom - line.top), 1f)
                if (ratio > bestRatio) {
                    bestRatio = ratio
                    best = line
                }
            }
            if (best != null && bestRatio >= minOverlap) {
                best.strokes += index to stroke
                best.flat = best.flat && isFlat
                best.top = min(best.top, top)
                best.bottom = max(best.bottom, bottom)
            } else {
                lines += Line(top, bottom, mutableListOf(index to stroke), isFlat)
            }
        }

        fun gap(a: Line, b: Line) = max(0f, max(a.top, b.top) - min(a.bottom, b.bottom))
        fun absorb(target: Line, from: Line) {
            target.strokes += from.strokes
            target.top = min(target.top, from.top)
            target.bottom = max(target.bottom, from.bottom)
            from.strokes.clear()
        }

        val textLines = lines.filter { !it.flat }
        if (textLines.isNotEmpty()) {
            // 只含扁平笔画（下划线、横线、点）的行不单独成行，并入垂直距离最近的正常行；太远则保留。
            for (flatLine in lines.filter { it.flat }) {
                val target = textLines.minByOrNull { gap(flatLine, it) } ?: continue
                if (gap(flatLine, target) <= medianHeight) absorb(target, flatLine)
            }
        } else if (lines.size > 1) {
            // 整页都是扁平笔画（例如「二」）：扁平行之间就近合并，间距上限取笔画宽度中位数的一半。
            val widths = items.filter { it.second.height <= tallLimit }.map { it.second.right - it.second.left }.sorted()
            val limit = max(widths[widths.size / 2] * 0.5f, minHeight)
            while (true) {
                val alive = lines.filter { it.strokes.isNotEmpty() }
                var pa: Line? = null
                var pb: Line? = null
                var pg = Float.MAX_VALUE
                for (i in alive.indices) for (j in i + 1 until alive.size) {
                    val g = gap(alive[i], alive[j])
                    if (g <= limit && g < pg) { pg = g; pa = alive[i]; pb = alive[j] }
                }
                if (pa == null || pb == null) break
                absorb(pa, pb)
            }
        }
        val result = lines.filter { it.strokes.isNotEmpty() }.toMutableList()

        if (tall.isNotEmpty()) {
            if (result.isEmpty()) {
                result += Line(0f, 0f, tall, false)
            } else {
                for (t in tall) {
                    val b = boundsOf(t.second)
                    val centre = (b.top + b.bottom) / 2f
                    result.minByOrNull { abs((it.top + it.bottom) / 2f - centre) }!!.strokes += t
                }
            }
        }
        return result.sortedBy { it.top }.map { line -> line.strokes.sortedBy { it.first }.map { it.second } }
    }
}
