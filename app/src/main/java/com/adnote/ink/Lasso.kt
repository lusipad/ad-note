package com.adnote.ink

import com.adnote.model.InkPoint
import com.adnote.model.Stroke

/** 套索选择与选区变换。 */
object Lasso {

    /** 一条笔画有至少这个比例的采样点落在套索内即视为选中。 */
    private const val INSIDE_RATIO = 0.6f

    /** 返回被套索圈中的笔画 id。套索少于 3 个点时不选中任何笔画。 */
    fun select(strokes: List<Stroke>, lasso: List<InkPoint>): Set<String> {
        if (lasso.size < 3) return emptySet()
        val out = HashSet<String>()
        for (s in strokes) {
            if (s.points.isEmpty()) continue
            val inside = s.points.count { contains(lasso, it.x, it.y) }
            if (inside >= s.points.size * INSIDE_RATIO) out += s.id
        }
        return out
    }

    /** 射线法判断点是否在多边形内（多边形自动闭合）。 */
    fun contains(polygon: List<InkPoint>, x: Float, y: Float): Boolean {
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val a = polygon[i]; val b = polygon[j]
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /** 把选中的笔画整体平移 (dx, dy)。 */
    fun translate(strokes: List<Stroke>, ids: Set<String>, dx: Float, dy: Float): List<Stroke> {
        if (ids.isEmpty() || (dx == 0f && dy == 0f)) return strokes
        return strokes.map { s ->
            if (s.id in ids) s.copy(points = s.points.map { it.copy(x = it.x + dx, y = it.y + dy) }) else s
        }
    }

    /** 修改选中笔画的颜色。 */
    fun recolor(strokes: List<Stroke>, ids: Set<String>, color: String): List<Stroke> =
        strokes.map { if (it.id in ids) it.copy(color = color) else it }
}
