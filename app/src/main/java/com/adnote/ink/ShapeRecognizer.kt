package com.adnote.ink

import com.adnote.model.InkPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * 手绘形状识别：把歪歪扭扭的直线、单笔箭头、三角形、矩形、五边形、六边形、椭圆/圆规整成标准图形。
 * 输出仍是采样点列表，作为普通笔画保存，导出与同步无需任何特殊处理。
 */
object ShapeRecognizer {

    enum class Kind { LINE, ARROW, TRIANGLE, RECTANGLE, QUAD, PENTAGON, HEXAGON, ELLIPSE, CIRCLE }

    data class Result(val kind: Kind, val points: List<InkPoint>)

    /**
     * 「画完停住」检测：笔尖在末端 [radius] 像素内停留至少 [holdMs] 毫秒。
     * 返回停留开始的下标（识别时只取这之前的点），未停住返回 -1。
     */
    fun holdStartIndex(points: List<InkPoint>, holdMs: Long = 450, radius: Float = 24f): Int {
        if (points.size < 3) return -1
        val last = points.last()
        var k = points.lastIndex
        while (k > 0 && hypot(points[k - 1].x - last.x, points[k - 1].y - last.y) <= radius) k--
        return if (last.t - points[k].t >= holdMs && k >= 2) k else -1
    }

    /** 检查尾部点是否在末端聚集（静止停顿状态）。 */
    fun isStillAtEnd(points: List<InkPoint>, radius: Float = 24f, minTailPoints: Int = 3): Boolean {
        if (points.size < minTailPoints) return false
        val last = points.last()
        val tail = points.takeLast(minTailPoints)
        return tail.all { hypot(it.x - last.x, it.y - last.y) <= radius }
    }

    /**
     * 当处于 Draw & Hold 状态时，随着笔尖继续移动，动态以起点为锚点拉伸直线/箭头。
     */
    fun stretch(kind: Kind, basePoints: List<InkPoint>, currentTip: InkPoint): List<InkPoint> {
        val start = basePoints.firstOrNull() ?: return listOf(currentTip)
        val pressure = currentTip.pressure
        return when (kind) {
            Kind.LINE -> {
                var p1 = start
                var p2 = currentTip
                val dx = p2.x - p1.x
                val dy = p2.y - p1.y
                val angleRad = abs(atan2(dy, dx))
                if (angleRad < 0.08 || angleRad > PI - 0.08) {
                    val avgY = (p1.y + p2.y) / 2f
                    p1 = p1.copy(y = avgY)
                    p2 = p2.copy(y = avgY)
                } else if (abs(angleRad - PI / 2) < 0.08) {
                    val avgX = (p1.x + p2.x) / 2f
                    p1 = p1.copy(x = avgX)
                    p2 = p2.copy(x = avgX)
                }
                densify(listOf(p1, p2), pressure)
            }
            Kind.ARROW -> {
                val shaftLen = hypot(currentTip.x - start.x, currentTip.y - start.y)
                if (shaftLen < 20f) return densify(listOf(start, currentTip), pressure)
                val angle = atan2(currentTip.y - start.y, currentTip.x - start.x)
                val headLen = (shaftLen * 0.18f).coerceIn(16f, 48f)
                val wingAngle = (PI * 0.82).toFloat()
                val wing1 = InkPoint(
                    currentTip.x + headLen * cos(angle + wingAngle),
                    currentTip.y + headLen * sin(angle + wingAngle),
                    pressure,
                )
                val wing2 = InkPoint(
                    currentTip.x + headLen * cos(angle - wingAngle),
                    currentTip.y + headLen * sin(angle - wingAngle),
                    pressure,
                )
                densify(listOf(start, currentTip, wing1, currentTip, wing2), pressure)
            }
            else -> basePoints
        }
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

        // 直线：所有点贴近首尾连线，且路径长度接近弦长，带水平/垂直智能吸附
        if (chord > 0.75f * diag && chord > 0.80f * pathLen) {
            val maxDev = pts.maxOf { Eraser.distToSegment(it, first, last) }
            if (maxDev < max(0.08f * chord, 7f)) {
                var p1 = first
                var p2 = last
                val dx = p2.x - p1.x
                val dy = p2.y - p1.y
                val angleRad = abs(atan2(dy, dx))
                // 水平吸附 (近 0° 或 180°，容差约 4.5°)
                if (angleRad < 0.08 || angleRad > PI - 0.08) {
                    val avgY = (p1.y + p2.y) / 2f
                    p1 = p1.copy(y = avgY)
                    p2 = p2.copy(y = avgY)
                } else if (abs(angleRad - PI / 2) < 0.08) {
                    // 垂直吸附 (近 90°，容差约 4.5°)
                    val avgX = (p1.x + p2.x) / 2f
                    p1 = p1.copy(x = avgX)
                    p2 = p2.copy(x = avgX)
                }
                return Result(Kind.LINE, densify(listOf(p1, p2), pressure))
            }
        }

        // 单笔箭头（主干直线 + 末端折返箭头翼）
        recognizeArrow(pts, diag, pressure)?.let { return it }

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
            5 -> return Result(Kind.PENTAGON, densify(corners + corners.first(), pressure))
            6 -> return Result(Kind.HEXAGON, densify(corners + corners.first(), pressure))
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

    /**
     * 识别一笔连画的箭头：前半段（至少占总点数 55%）为直线主干，末端在顶点附近折返画出箭头翼。
     */
    private fun recognizeArrow(pts: List<InkPoint>, diag: Float, pressure: Float): Result? {
        if (pts.size < 6 || diag < 40f) return null
        val start = pts.first()
        // 找到离起点最远的点作为箭头尖端候选
        val tipIdx = pts.indices.maxByOrNull { hypot(pts[it].x - start.x, pts[it].y - start.y) } ?: return null
        if (tipIdx < pts.size / 2 || tipIdx >= pts.lastIndex) return null
        val tip = pts[tipIdx]
        val shaftLen = hypot(tip.x - start.x, tip.y - start.y)
        if (shaftLen < 0.7f * diag) return null

        val shaftPts = pts.subList(0, tipIdx + 1)
        val maxShaftDev = shaftPts.maxOf { Eraser.distToSegment(it, start, tip) }
        if (maxShaftDev > max(0.08f * shaftLen, 8f)) return null

        val tailPts = pts.subList(tipIdx, pts.size)
        val tailLen = pathLength(tailPts)
        if (tailLen < 0.08f * shaftLen || tailLen > 0.75f * shaftLen) return null

        // 生成标准对称箭头折线：start -> tip -> 左翼 -> tip -> 右翼
        val angle = atan2(tip.y - start.y, tip.x - start.x)
        val headLen = (shaftLen * 0.18f).coerceIn(16f, 48f)
        val wingAngle = (PI * 0.82).toFloat()
        val wing1 = InkPoint(
            tip.x + headLen * cos(angle + wingAngle),
            tip.y + headLen * sin(angle + wingAngle),
            pressure,
        )
        val wing2 = InkPoint(
            tip.x + headLen * cos(angle - wingAngle),
            tip.y + headLen * sin(angle - wingAngle),
            pressure,
        )
        val vertices = listOf(start, tip, wing1, tip, wing2)
        return Result(Kind.ARROW, densify(vertices, pressure))
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
