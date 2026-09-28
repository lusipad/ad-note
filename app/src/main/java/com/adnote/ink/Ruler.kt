package com.adnote.ink

import com.adnote.model.InkPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 直尺：中心 (cx, cy)、方向角 angle（弧度）、长度与宽度都是页面坐标。
 * 靠近直尺边缘落笔的笔画会被吸附成贴着尺边的直线。
 */
data class Ruler(
    val cx: Float,
    val cy: Float,
    val angle: Float = 0f,
    val length: Float = 1100f,
    val width: Float = 96f,
) {
    private val ux get() = cos(angle)
    private val uy get() = sin(angle)

    /** 点在直尺坐标系下的 (沿尺方向, 法向) 分量。 */
    private fun local(x: Float, y: Float): Pair<Float, Float> {
        val dx = x - cx; val dy = y - cy
        return (dx * ux + dy * uy) to (-dx * uy + dy * ux)
    }

    /** 点是否落在直尺本体上（用于手指拖动、旋转直尺）。 */
    fun contains(x: Float, y: Float): Boolean {
        val (a, n) = local(x, y)
        return abs(a) <= length / 2f && abs(n) <= width / 2f
    }

    /** 四个角的页面坐标，按顺时针排列。 */
    fun corners(): List<Pair<Float, Float>> {
        val hl = length / 2f; val hw = width / 2f
        return listOf(-hl to -hw, hl to -hw, hl to hw, -hl to hw).map { (a, n) ->
            (cx + a * ux - n * uy) to (cy + a * uy + n * ux)
        }
    }

    fun moved(dx: Float, dy: Float) = copy(cx = cx + dx, cy = cy + dy)

    fun rotated(delta: Float) = copy(angle = angle + delta)

    /**
     * 如果笔画起点贴近某条尺边（[snapDist] 以内），返回吸附到该尺边上的直线；否则返回 null。
     * 吸附后的线段覆盖原笔画在尺边方向上的投影范围。
     */
    fun snap(points: List<InkPoint>, snapDist: Float = 28f): List<InkPoint>? {
        if (points.size < 2) return null
        val (a0, n0) = local(points.first().x, points.first().y)
        if (abs(a0) > length / 2f + snapDist) return null
        val edge = when {
            abs(n0 + width / 2f) <= snapDist -> -width / 2f
            abs(n0 - width / 2f) <= snapDist -> width / 2f
            else -> return null
        }
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (p in points) {
            val (a, _) = local(p.x, p.y)
            lo = min(lo, a); hi = max(hi, a)
        }
        if (hi - lo < 4f) return null
        val pressure = points.map { it.pressure }.sorted()[points.size / 2]
        val steps = max(1, ((hi - lo) / 6f).toInt())
        // 按书写方向输出
        val forward = local(points.last().x, points.last().y).first >= a0
        return (0..steps).map { i ->
            val t = i.toFloat() / steps
            val a = if (forward) lo + (hi - lo) * t else hi - (hi - lo) * t
            InkPoint(cx + a * ux - edge * uy, cy + a * uy + edge * ux, pressure, points.first().t)
        }
    }
}
