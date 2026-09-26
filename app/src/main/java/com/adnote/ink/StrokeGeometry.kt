package com.adnote.ink

import com.adnote.model.InkPoint
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

    /** 压感映射到宽度倍率：轻压 0.4 倍，重压 1.2 倍。 */
    fun widthAt(base: Float, pressure: Float): Float =
        base * (0.4f + 0.8f * pressure.coerceIn(0f, 1f))

    /**
     * 返回轮廓多边形的顶点（左侧正向 + 右侧反向）。
     * 单点笔画返回空列表，由调用方画成圆点。
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
            val half = widthAt(stroke.width, pts[i].pressure) / 2f
            // 法线 (-dy, dx)
            left += Pt(pts[i].x - dy * half, pts[i].y + dx * half)
            right += Pt(pts[i].x + dy * half, pts[i].y - dx * half)
        }
        return left + right.asReversed()
    }

    /** 笔画的包围盒 [minX, minY, maxX, maxY]，已包含笔宽。 */
    fun bounds(stroke: Stroke): FloatArray {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in stroke.points) {
            minX = min(minX, p.x); minY = min(minY, p.y)
            maxX = max(maxX, p.x); maxY = max(maxY, p.y)
        }
        val pad = stroke.width
        return floatArrayOf(minX - pad, minY - pad, maxX + pad, maxY + pad)
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
