package com.adnote.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.adnote.ink.StrokeGeometry
import com.adnote.model.Page
import com.adnote.model.Stroke

/**
 * 在 Android Canvas 上绘制笔画，屏幕、缩略图与 PDF 导出共用。
 * 每个实例持有自己的 Paint/Path，不同线程各用各的实例即可。
 */
class StrokePainter {

    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val linePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    /**
     * 每条笔画的轮廓路径缓存。整页重画（翻页、撤销、抬笔后刷新）时不必每次都重新计算全部笔画的轮廓，
     * 笔迹多的页面在墨水屏设备较弱的处理器上差别明显。笔画是不可变的，按 id 查、再核对是同一个对象。
     */
    private val pathCache = object : LinkedHashMap<String, Pair<Stroke, Path>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Stroke, Path>>?) = size > MAX_CACHED_PATHS
    }

    fun drawAll(canvas: Canvas, strokes: List<Stroke>) {
        val (highlighters, normalStrokes) = strokes.partition { it.pen == com.adnote.model.PenType.HIGHLIGHTER }
        for (s in highlighters) draw(canvas, s)
        for (s in normalStrokes) draw(canvas, s)
    }

    fun draw(canvas: Canvas, stroke: Stroke) {
        if (stroke.points.isEmpty()) return
        val color = parseColor(stroke.color)
        val alpha = (stroke.pen.opacity * 255).toInt().coerceIn(0, 255)

        if (stroke.pen.constantWidth && stroke.points.size > 1) {
            linePaint.color = color
            linePaint.alpha = alpha
            linePaint.strokeWidth = stroke.width
            canvas.drawPath(cachedPath(stroke) ?: return, linePaint)
            return
        }

        fillPaint.color = color
        fillPaint.alpha = alpha
        val outline = cachedPath(stroke)
        if (outline != null) {
            canvas.drawPath(outline, fillPaint)
        } else {
            val pt = stroke.points[0]
            canvas.drawCircle(pt.x, pt.y, StrokeGeometry.dotRadius(stroke), fillPaint)
        }
    }

    /** 等宽笔返回中心折线，其余返回轮廓多边形；单点笔画返回 null（画成圆点）。 */
    private fun cachedPath(stroke: Stroke): Path? {
        pathCache[stroke.id]?.let { (s, p) -> if (s === stroke) return p }
        val p = buildPath(stroke) ?: return null
        pathCache[stroke.id] = stroke to p
        return p
    }

    private fun buildPath(stroke: Stroke): Path? {
        if (stroke.pen.constantWidth) {
            val line = StrokeGeometry.centerline(stroke)
            if (line.size < 2) return null
            return Path().apply {
                moveTo(line[0].x, line[0].y)
                if (line.size == 2) {
                    lineTo(line[1].x, line[1].y)
                } else {
                    for (i in 1 until line.size - 1) {
                        val midX = (line[i].x + line[i + 1].x) / 2f
                        val midY = (line[i].y + line[i + 1].y) / 2f
                        quadTo(line[i].x, line[i].y, midX, midY)
                    }
                    lineTo(line.last().x, line.last().y)
                }
            }
        }
        val outline = StrokeGeometry.outline(stroke)
        if (outline.isEmpty()) return null
        val pts = stroke.points
        val startPt = pts.firstOrNull()
        val endPt = pts.lastOrNull()
        val halfSize = outline.size / 2

        return Path().apply {
            moveTo(outline[0].x, outline[0].y)
            if (halfSize < 2 || startPt == null || endPt == null) {
                for (i in 1 until outline.size) lineTo(outline[i].x, outline[i].y)
            } else {
                // 左侧轮廓：二次贝塞尔平滑
                for (i in 1 until halfSize - 1) {
                    val midX = (outline[i].x + outline[i + 1].x) / 2f
                    val midY = (outline[i].y + outline[i + 1].y) / 2f
                    quadTo(outline[i].x, outline[i].y, midX, midY)
                }
                lineTo(outline[halfSize - 1].x, outline[halfSize - 1].y)

                // 笔画末端圆弧过渡
                quadTo(endPt.x, endPt.y, outline[halfSize].x, outline[halfSize].y)

                // 右侧轮廓：反向二次贝塞尔平滑
                for (i in halfSize + 1 until outline.size - 1) {
                    val midX = (outline[i].x + outline[i + 1].x) / 2f
                    val midY = (outline[i].y + outline[i + 1].y) / 2f
                    quadTo(outline[i].x, outline[i].y, midX, midY)
                }
                lineTo(outline.last().x, outline.last().y)

                // 笔画起笔端圆弧过渡回到起点
                quadTo(startPt.x, startPt.y, outline[0].x, outline[0].y)
            }
            close()
        }
    }

    /** 绘制一整页（底纹 + 笔迹）到 [canvas]，用于缩略图。canvas 需已按页面尺寸缩放。 */
    fun drawPage(canvas: Canvas, page: Page, drawTemplate: Boolean = true) {
        if (drawTemplate) {
            PageTemplateRenderer.render(canvas, page.template, page.backgroundColor, page.width, page.height)
        }
        drawAll(canvas, page.strokes)
    }

    companion object {
        private const val MAX_CACHED_PATHS = 8_000

        fun parseColor(hex: String?): Int {
            if (hex.isNullOrBlank()) return Color.BLACK
            return try {
                Color.parseColor(hex)
            } catch (_: Exception) {
                Color.BLACK
            }
        }
    }
}
