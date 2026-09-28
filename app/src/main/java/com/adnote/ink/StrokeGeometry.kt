package com.adnote.ink

import com.adnote.model.InkPoint
import com.adnote.model.PenType
import com.adnote.model.Stroke
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 把一条笔画转换成可填充的轮廓多边形。
 *
 * 屏幕渲染（Canvas）和 SVG 导出共用这一份几何计算，保证两边笔迹粗细一致。
 */
object StrokeGeometry {

    data class Pt(val x: Float, val y: Float)

    /** 毛笔起笔/收笔收细覆盖的采样点数。 */
    private const val TAPER_POINTS = 6

    /** 压感映射到宽度倍率（钢笔）：轻压 0.4 倍，重压 1.2 倍。 */
    fun widthAt(base: Float, pressure: Float): Float =
        base * PenType.FOUNTAIN.widthFactor(pressure)

    /** 按笔型计算某个压感下的笔宽。 */
    fun widthAt(stroke: Stroke, pressure: Float): Float =
        stroke.width * stroke.pen.widthFactor(pressure)

    /**
     * 返回轮廓多边形的顶点（左侧正向 + 右侧反向）。
     * 单点笔画返回空列表，由调用方画成圆点。等宽笔（马克笔、荧光笔）应改用 [centerline] 描边。
     */
    fun outline(stroke: Stroke): List<Pt> {
        val pts = dedupe(stroke.points)
        if (pts.size < 2) return emptyList()
        val left = ArrayList<Pt>(pts.size)
        val right = ArrayList<Pt>(pts.size)
        for (i in pts.indices) {
            val prev = pts[max(i - 1, 0)]
            val next = pts[min(i + 1, pts.lastIndex)]
            var dx = next.x - prev.x
            var dy = next.y - prev.y
            val len = hypot(dx, dy)
            if (len == 0f) { dx = 1f; dy = 0f } else { dx /= len; dy /= len }
            var half = widthAt(stroke, pts[i].pressure) / 2f * tiltFactor(stroke.pen, pts[i].tilt)
            if (stroke.pen.taper) half *= taperFactor(i, pts.size)
            // 法线 (-dy, dx)
            left += Pt(pts[i].x - dy * half, pts[i].y + dx * half)
            right += Pt(pts[i].x + dy * half, pts[i].y - dx * half)
        }
        return left + right.asReversed()
    }

    /** 等宽笔的中心折线（已去重）。 */
    fun centerline(stroke: Stroke): List<Pt> = dedupe(stroke.points).map { Pt(it.x, it.y) }

    /** 单点笔画（点按）的圆点半径。 */
    fun dotRadius(stroke: Stroke): Float {
        val p = stroke.points.firstOrNull() ?: return stroke.width / 2f
        return widthAt(stroke, p.pressure) / 2f
    }

    /** 铅笔侧锋：笔身越倾斜线条越宽，完全放平时最多 2.5 倍。其他笔型不受倾角影响。 */
    internal fun tiltFactor(pen: PenType, tilt: Float): Float {
        if (pen != PenType.PENCIL || tilt <= 0f) return 1f
        return 1f + 1.5f * (tilt / (Math.PI.toFloat() / 2f)).coerceIn(0f, 1f)
    }

    /** 毛笔两端收细：端点处 0.25 倍，逐渐过渡到 1 倍。 */
    internal fun taperFactor(index: Int, count: Int): Float {
        val span = min(TAPER_POINTS, count / 3)
        if (span <= 0) return 1f
        val d = min(index, count - 1 - index)
        if (d >= span) return 1f
        return 0.25f + 0.75f * (d.toFloat() / span)
    }

    /** 笔画的包围盒 [minX, minY, maxX, maxY]，已包含笔宽。 */
    fun bounds(stroke: Stroke): FloatArray {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in stroke.points) {
            minX = min(minX, p.x); minY = min(minY, p.y)
            maxX = max(maxX, p.x); maxY = max(maxY, p.y)
        }
        // 各笔型最大半宽都不超过 0.95 倍基准笔宽，用整笔宽做边距足够
        val pad = stroke.width
        return floatArrayOf(minX - pad, minY - pad, maxX + pad, maxY + pad)
    }

    /** 多条笔画的联合包围盒；列表为空时返回 null。 */
    fun bounds(strokes: Collection<Stroke>): FloatArray? {
        if (strokes.isEmpty()) return null
        val out = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (s in strokes) {
            val b = bounds(s)
            out[0] = min(out[0], b[0]); out[1] = min(out[1], b[1])
            out[2] = max(out[2], b[2]); out[3] = max(out[3], b[3])
        }
        return out
    }

    /** 去掉连续重复点，避免法线方向计算出 NaN。 */
    private fun dedupe(points: List<InkPoint>): List<InkPoint> {
        if (points.size < 2) return points
        val out = ArrayList<InkPoint>(points.size)
        out += points[0]
        for (i in 1 until points.size) {
            val p = points[i]; val q = out.last()
            if (p.x != q.x || p.y != q.y) out += p
        }
        return out
    }
}
