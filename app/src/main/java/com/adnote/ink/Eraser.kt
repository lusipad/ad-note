package com.adnote.ink

import com.adnote.model.InkPoint
import com.adnote.model.Stroke
import com.adnote.model.newId
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 橡皮擦几何。
 * - [hitStrokes]：整笔擦除，橡皮轨迹经过某条笔画附近或相交时，删除整条笔画；
 * - [erasePartial]：局部擦除，只去掉橡皮扫过的那一段，剩余部分拆分成新的笔画。
 */
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

    /**
     * 局部擦除。返回擦除后的笔画列表；没有任何笔画被触及时返回原列表（同一对象）。
     *
     * 做法：把被触及笔画的采样点重采样到不超过半径 1/3 的间距，删掉落在橡皮范围内的点，
     * 剩余连续片段各自成为一条新笔画（保留颜色、笔型和粗细）。
     */
    fun erasePartial(strokes: List<Stroke>, eraserPath: List<InkPoint>, radius: Float): List<Stroke> {
        if (eraserPath.isEmpty() || strokes.isEmpty()) return strokes
        var eMinX = Float.MAX_VALUE; var eMinY = Float.MAX_VALUE
        var eMaxX = -Float.MAX_VALUE; var eMaxY = -Float.MAX_VALUE
        for (e in eraserPath) {
            eMinX = min(eMinX, e.x); eMinY = min(eMinY, e.y)
            eMaxX = max(eMaxX, e.x); eMaxY = max(eMaxY, e.y)
        }

        var changed = false
        val out = ArrayList<Stroke>(strokes.size)
        for (s in strokes) {
            val b = StrokeGeometry.bounds(s)
            val overlap = !(eMaxX < b[0] - radius || eMinX > b[2] + radius ||
                            eMaxY < b[1] - radius || eMinY > b[3] + radius)
            if (!overlap) { out += s; continue }

            val threshold = radius + s.width / 2f
            if (s.points.size == 1) {
                if (distToPath(s.points[0], eraserPath) <= threshold) changed = true else out += s
                continue
            }

            val dense = resample(s.points, max(radius / 3f, 1f))
            val keep = BooleanArray(dense.size) { distToPath(dense[it], eraserPath) > threshold }
            if (keep.all { it }) { out += s; continue }

            changed = true
            var start = -1
            for (i in 0..dense.size) {
                val k = i < dense.size && keep[i]
                if (k && start < 0) start = i
                if (!k && start >= 0) {
                    // 只剩一个点的碎片视为被擦掉，避免留下孤立墨点
                    if (i - start >= 2) out += s.copy(id = newId(), points = dense.subList(start, i).toList())
                    start = -1
                }
            }
        }
        return if (changed) out else strokes
    }

    /** 在相邻采样点之间线性插值，使间距不超过 [step]。 */
    internal fun resample(points: List<InkPoint>, step: Float): List<InkPoint> {
        if (points.size < 2) return points
        val out = ArrayList<InkPoint>(points.size * 2)
        out += points[0]
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            val d = hypot(b.x - a.x, b.y - a.y)
            val n = (d / step).toInt()
            for (k in 1..n) {
                val t = k / (n + 1f)
                out += InkPoint(
                    x = a.x + (b.x - a.x) * t,
                    y = a.y + (b.y - a.y) * t,
                    pressure = a.pressure + (b.pressure - a.pressure) * t,
                    t = a.t + ((b.t - a.t) * t).toLong(),
                )
            }
            out += b
        }
        return out
    }

    private fun distToPath(p: InkPoint, path: List<InkPoint>): Float {
        if (path.size == 1) return hypot(p.x - path[0].x, p.y - path[0].y)
        var best = Float.MAX_VALUE
        for (j in 0 until path.size - 1) {
            best = min(best, distToSegment(p, path[j], path[j + 1]))
        }
        return best
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
