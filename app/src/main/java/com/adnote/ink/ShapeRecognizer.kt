package com.adnote.ink

import com.adnote.model.InkPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * 手绘形状识别：把歪歪扭扭的直线、三角形、矩形、椭圆/圆规整成标准图形。
 * 输出仍是采样点列表，作为普通笔画保存，导出与同步无需任何特殊处理。
 */
object ShapeRecognizer {

    enum class Kind { LINE, TRIANGLE, RECTANGLE, QUAD, ELLIPSE, CIRCLE }

    data class Result(val kind: Kind, val points: List<InkPoint>)

    /**
     * 「画完停住」检测：笔尖在末端 [radius] 像素内停留至少 [holdMs] 毫秒。
     * 返回停留开始的下标（识别时只取这之前的点），未停住返回 -1。
     */
    fun holdStartIndex(points: List<InkPoint>, holdMs: Long = 500, radius: Float = 8f): Int {
        if (points.size < 3) return -1
        val last = points.last()
        var k = points.lastIndex
        while (k > 0 && hypot(points[k - 1].x - last.x, points[k - 1].y - last.y) <= radius) k--
        return if (last.t - points[k].t >= holdMs && k >= 2) k else -1
    }

    fun recognize(input: List<InkPoint>): Result? {
        val pts = dedupe(input)
        if (pts.size < 4) return null
        val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }; val maxY = pts.maxOf { it.y }
        val diag = hypot(maxX - minX, maxY - minY)
        if (diag < 24f) return null
        val pressure = pts.map { it.pressure }.sorted()[pts.size / 2]

        val first = pts.first(); val last = pts.last()
        val chord = hypot(last.x - first.x, last.y - first.y)
        val pathLen = pathLength(pts)

        // 直线：所有点贴近首尾连线，且路径长度接近弦长
        if (chord > 0.8f * diag && chord > 0.85f * pathLen) {
            val maxDev = pts.maxOf { Eraser.distToSegment(it, first, last) }
            if (maxDev < max(0.06f * chord, 5f)) {
                return Result(Kind.LINE, densify(listOf(first, last), pressure))
            }
        }

        // 闭合图形：首尾距离小于包围盒对角线的 25%
        if (chord > 0.25f * diag) return null
        val closed = pts + pts.first()
        val simplified = rdp(closed, 0.07f * diag).dropLast(1)
        val corners = mergeClose(simplified, 0.12f * diag)

        when (corners.size) {
            3 -> return Result(Kind.TRIANGLE, densify(corners + corners.first(), pressure))
            4 -> {
                if (corners.indices.all { abs(angleAt(corners, it) - PI / 2) < PI / 8 }) {
                    val axisAligned = corners.indices.all { i ->
                        val a = corners[i]; val b = corners[(i + 1) % 4]
                        val ang = abs(Math.atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())) % (PI / 2)
                        ang < PI / 14 || ang > PI / 2 - PI / 14
                    }
                    if (axisAligned) {
                        val rect = listOf(
                            InkPoint(minX, minY), InkPoint(maxX, minY), InkPoint(maxX, maxY), InkPoint(minX, maxY),
                        )
                        return Result(Kind.RECTANGLE, densify(rect + rect.first(), pressure))
                    }
                }
                return Result(Kind.QUAD, densify(corners + corners.first(), pressure))
            }
        }

        // 椭圆：用包围盒拟合，检查各点到椭圆的归一化误差
        val cx = (minX + maxX) / 2f; val cy = (minY + maxY) / 2f
        var rx = (maxX - minX) / 2f; var ry = (maxY - minY) / 2f
        if (rx < 4f || ry < 4f) return null
        val err = pts.map { p ->
            val nx = (p.x - cx) / rx; val ny = (p.y - cy) / ry
            abs(hypot(nx, ny) - 1f)
        }.average()
        if (err > 0.18) return null
        val circle = abs(rx - ry) / max(rx, ry) < 0.15f
        if (circle) { val r = (rx + ry) / 2f; rx = r; ry = r }
        val n = 72
        val ring = (0..n).map { i ->
            val a = 2 * PI * i / n
            InkPoint(cx + rx * cos(a).toFloat(), cy + ry * sin(a).toFloat(), pressure)
        }
        return Result(if (circle) Kind.CIRCLE else Kind.ELLIPSE, ring)
    }

    /** 顶点 i 处的内角（弧度）。 */
    private fun angleAt(poly: List<InkPoint>, i: Int): Double {
        val p = poly[i]
        val a = poly[(i - 1 + poly.size) % poly.size]
        val b = poly[(i + 1) % poly.size]
        val v1x = a.x - p.x; val v1y = a.y - p.y
        val v2x = b.x - p.x; val v2y = b.y - p.y
        val d = hypot(v1x, v1y) * hypot(v2x, v2y)
        if (d == 0f) return 0.0
        return acos(((v1x * v2x + v1y * v2y) / d).toDouble().coerceIn(-1.0, 1.0))
    }

    /** Ramer–Douglas–Peucker 折线简化。 */
    internal fun rdp(points: List<InkPoint>, epsilon: Float): List<InkPoint> {
        if (points.size < 3) return points
        var maxD = 0f; var idx = 0
        for (i in 1 until points.lastIndex) {
            val d = Eraser.distToSegment(points[i], points.first(), points.last())
            if (d > maxD) { maxD = d; idx = i }
        }
        if (maxD <= epsilon) return listOf(points.first(), points.last())
        val left = rdp(points.subList(0, idx + 1), epsilon)
        val right = rdp(points.subList(idx, points.size), epsilon)
        return left.dropLast(1) + right
    }

    /** 合并相距过近的顶点（包括首尾相接处）。 */
    private fun mergeClose(poly: List<InkPoint>, minDist: Float): List<InkPoint> {
        val out = ArrayList<InkPoint>()
        for (p in poly) {
            if (out.isEmpty() || hypot(p.x - out.last().x, p.y - out.last().y) >= minDist) out += p
        }
        while (out.size > 2 && hypot(out.first().x - out.last().x, out.first().y - out.last().y) < minDist) {
            out.removeAt(out.lastIndex)
        }
        return out
    }

    /** 沿各边每隔约 6 像素插点，让笔画轮廓在拐角处也饱满。 */
    internal fun densify(vertices: List<InkPoint>, pressure: Float, step: Float = 6f): List<InkPoint> {
        val out = ArrayList<InkPoint>()
        for (i in 0 until vertices.lastIndex) {
            val a = vertices[i]; val b = vertices[i + 1]
            val n = max(1, (hypot(b.x - a.x, b.y - a.y) / step).toInt())
            for (k in 0 until n) {
                val t = k.toFloat() / n
                out += InkPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, pressure)
            }
        }
        val end = vertices.last()
        out += InkPoint(end.x, end.y, pressure)
        return out
    }

    private fun pathLength(pts: List<InkPoint>): Float {
        var len = 0f
        for (i in 1 until pts.size) len += hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
        return len
    }

    private fun dedupe(points: List<InkPoint>): List<InkPoint> {
        val out = ArrayList<InkPoint>(points.size)
        for (p in points) if (out.isEmpty() || out.last().x != p.x || out.last().y != p.y) out += p
        return out
    }
}
