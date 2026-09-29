package com.adnote.ink

import com.adnote.model.PenType
import com.adnote.model.Stroke
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

        val lines = ArrayList<Line>()
        for ((index, item) in items.withIndex()) {
            val (stroke, b) = item
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

        // 只含扁平笔画（下划线、横线、点）的行不单独成行，并入垂直距离最近的正常行；太远则保留。
        fun gap(a: Line, b: Line) = max(0f, max(a.top, b.top) - min(a.bottom, b.bottom))
        val textLines = lines.filter { !it.flat }
        val result = if (textLines.isEmpty()) lines else {
            for (flatLine in lines.filter { it.flat }) {
                val target = textLines.minByOrNull { gap(flatLine, it) } ?: continue
                if (gap(flatLine, target) <= medianHeight) {
                    target.strokes += flatLine.strokes
                    flatLine.strokes.clear()
                }
            }
            lines.filter { it.strokes.isNotEmpty() }
        }
        return result.sortedBy { it.top }.map { line -> line.strokes.sortedBy { it.first }.map { it.second } }
    }
}
