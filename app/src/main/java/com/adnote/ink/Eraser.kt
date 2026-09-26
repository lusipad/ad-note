package com.adnote.ink

import com.adnote.model.InkPoint
import com.adnote.model.Stroke
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** 整笔擦除：橡皮轨迹经过某条笔画附近或相交时，删除整条笔画。 */
object Eraser {

    /** 返回被橡皮轨迹命中的笔画 id。radius 为橡皮半径（像素）。 */
    fun hitStrokes(strokes: List<Stroke>, eraserPath: List<InkPoint>, radius: Float): Set<String> {
        if (eraserPath.isEmpty()) return emptySet()
        var eMinX = Float.MAX_VALUE; var eMinY = Float.MAX_VALUE
        var eMaxX = -Float.MAX_VALUE; var eMaxY = -Float.MAX_VALUE
        for (e in eraserPath) {
            eMinX = min(eMinX, e.x); eMinY = min(eMinY, e.y)
            eMaxX = max(eMaxX, e.x); eMaxY = max(eMaxY, e.y)
        }

        val hit = HashSet<String>()
        for (s in strokes) {
            val b = StrokeGeometry.bounds(s)
            val overlap = !(eMaxX < b[0] - radius || eMinX > b[2] + radius ||
                            eMaxY < b[1] - radius || eMinY > b[3] + radius)
            if (!overlap) continue
            if (intersects(s.points, eraserPath, radius + s.width / 2f)) hit += s.id
        }
        return hit
    }

    private fun intersects(stroke: List<InkPoint>, eraser: List<InkPoint>, threshold: Float): Boolean {
        if (stroke.isEmpty() || eraser.isEmpty()) return false

        // 单点笔画（点）
        if (stroke.size == 1) {
            val p = stroke[0]
            if (eraser.size == 1) return hypot(p.x - eraser[0].x, p.y - eraser[0].y) <= threshold
            for (j in 0 until eraser.size - 1) {
                if (distToSegment(p, eraser[j], eraser[j + 1]) <= threshold) return true
            }
            return false
        }

        // 单点橡皮点击
        if (eraser.size == 1) {
            val e = eraser[0]
            for (i in 0 until stroke.size - 1) {
                if (distToSegment(e, stroke[i], stroke[i + 1]) <= threshold) return true
            }
            return false
        }

        // 两个都是轨迹段
        for (i in 0 until stroke.size - 1) {
            val s1 = stroke[i]
            val s2 = stroke[i + 1]
            for (j in 0 until eraser.size - 1) {
                val e1 = eraser[j]
                val e2 = eraser[j + 1]
                if (segmentsIntersect(s1, s2, e1, e2)) return true
                if (distToSegment(e1, s1, s2) <= threshold) return true
                if (distToSegment(e2, s1, s2) <= threshold) return true
                if (distToSegment(s1, e1, e2) <= threshold) return true
                if (distToSegment(s2, e1, e2) <= threshold) return true
            }
        }
        return false
    }

    private fun ccw(a: InkPoint, b: InkPoint, c: InkPoint): Float =
        (c.y - a.y) * (b.x - a.x) - (b.y - a.y) * (c.x - a.x)

    private fun segmentsIntersect(a: InkPoint, b: InkPoint, c: InkPoint, d: InkPoint): Boolean {
        val cp1 = ccw(a, b, c)
        val cp2 = ccw(a, b, d)
        val cp3 = ccw(c, d, a)
        val cp4 = ccw(c, d, b)
        return ((cp1 > 0 && cp2 < 0) || (cp1 < 0 && cp2 > 0)) &&
               ((cp3 > 0 && cp4 < 0) || (cp3 < 0 && cp4 > 0))
    }

    internal fun distToSegment(p: InkPoint, a: InkPoint, b: InkPoint): Float {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }
}
