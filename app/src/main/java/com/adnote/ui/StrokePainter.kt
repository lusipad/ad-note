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
    private val path = Path()

    fun drawAll(canvas: Canvas, strokes: List<Stroke>) {
        for (s in strokes) draw(canvas, s)
    }

    fun draw(canvas: Canvas, stroke: Stroke) {
        if (stroke.points.isEmpty()) return
        val color = parseColor(stroke.color)
        val alpha = (stroke.pen.opacity * 255).toInt().coerceIn(0, 255)

        if (stroke.pen.constantWidth && stroke.points.size > 1) {
            val line = StrokeGeometry.centerline(stroke)
            path.reset()
            path.moveTo(line[0].x, line[0].y)
            for (i in 1 until line.size) path.lineTo(line[i].x, line[i].y)
            linePaint.color = color
            linePaint.alpha = alpha
            linePaint.strokeWidth = stroke.width
            canvas.drawPath(path, linePaint)
            return
        }

        fillPaint.color = color
        fillPaint.alpha = alpha
        val outline = StrokeGeometry.outline(stroke)
        if (outline.isNotEmpty()) {
            path.reset()
            path.moveTo(outline[0].x, outline[0].y)
            for (i in 1 until outline.size) path.lineTo(outline[i].x, outline[i].y)
            path.close()
            canvas.drawPath(path, fillPaint)
        } else {
            val pt = stroke.points[0]
            canvas.drawCircle(pt.x, pt.y, StrokeGeometry.dotRadius(stroke), fillPaint)
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
