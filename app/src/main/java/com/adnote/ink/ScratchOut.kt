package com.adnote.ink

import com.adnote.model.InkPoint
import com.adnote.model.Stroke
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * 「划掉即删除」：在已有笔迹上来回快速涂抹，删除被涂到的笔画，涂抹本身不留下笔迹。
 */
object ScratchOut {

    /** 至少多少次来回折返才算涂抹。 */
    private const val MIN_REVERSALS = 4

    /** 判断是否是涂抹手势：沿主方向来回折返多次，且路径很「密」。 */
    fun isScratch(points: List<InkPoint>): Boolean {
        if (points.size < 8) return false
        val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
        val w = maxX - minX; val h = maxY - minY
        val extent = max(w, h)
        if (extent < 20f) return false
        var len = 0f
        for (i in 1 until points.size) len += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        if (len < extent * 3.5f) return false
        val reversals = max(
            reversals(points.map { it.x }, max(4f, w * 0.15f)),
            reversals(points.map { it.y }, max(4f, h * 0.15f)),
        )
        return reversals >= MIN_REVERSALS
    }

    /** 统计一维序列的折返次数；[minTravel] 以下的抖动不计。 */
    internal fun reversals(values: List<Float>, minTravel: Float): Int {
        var count = 0
        var dir = 0
        var anchor = values.first()
        for (v in values) {
            val d = v - anchor
            if (abs(d) < minTravel) continue
            val nd = if (d > 0) 1 else -1
            if (dir != 0 && nd != dir) count++
            dir = nd
            anchor = v
        }
        return count
    }

    /**
     * 返回被涂抹的笔画 id：笔画包围盒中心落在涂抹范围内，并且与涂抹轨迹相交。
     * 返回空集表示这不是一次针对已有笔迹的涂抹，应当作普通笔画处理。
     */
    fun targets(strokes: List<Stroke>, scratch: List<InkPoint>): Set<String> {
        if (!isScratch(scratch)) return emptySet()
        val pad = 8f
        val minX = scratch.minOf { it.x } - pad; val maxX = scratch.maxOf { it.x } + pad
        val minY = scratch.minOf { it.y } - pad; val maxY = scratch.maxOf { it.y } + pad
        val candidates = strokes.filter { s ->
            val b = StrokeGeometry.bounds(s)
            val cx = (b[0] + b[2]) / 2f; val cy = (b[1] + b[3]) / 2f
            cx in minX..maxX && cy in minY..maxY &&
                // 笔画本身不能比涂抹范围大太多，避免一笔涂掉整段长线
                (b[2] - b[0]) <= (maxX - minX) * 1.6f && (b[3] - b[1]) <= (maxY - minY) * 1.6f
        }
        if (candidates.isEmpty()) return emptySet()
        return Eraser.hitStrokes(candidates, scratch, radius = 2f)
    }
}
