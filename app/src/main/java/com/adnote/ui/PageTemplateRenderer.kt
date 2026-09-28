package com.adnote.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import com.adnote.model.PageTemplate
import com.adnote.model.PaperPresets
import com.adnote.template.TemplateDot
import com.adnote.template.TemplateLayout
import com.adnote.template.TemplateLine
import com.adnote.template.TemplateRect

/**
 * 笔记本底纹渲染器：在 Android Canvas（屏幕、缩略图、PDF）上绘制纸张底色与底纹。
 * 几何由 [TemplateLayout] 统一给出，SVG 导出使用同一份图元，保证外观一致。
 */
object PageTemplateRenderer {

    private val linePaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val dashedPaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val colorCache = HashMap<String, Int>()

    /**
     * 在 Canvas 上绘制纸张底色与笔记本底纹。可在后台线程调用（生成缩略图时），因此加锁共享 Paint。
     *
     * @param minLine 线宽与圆点直径的下限（页面像素）。缩略图整体缩得很小时传入，避免格线淡到看不见。
     */
    @Synchronized
    fun render(
        canvas: Canvas,
        template: PageTemplate,
        backgroundColorHex: String,
        width: Int,
        height: Int,
        minLine: Float = 0f,
    ) {
        val paper = PaperPresets.find(backgroundColorHex)
        canvas.drawColor(color(paper.hex))
        renderLines(canvas, template, backgroundColorHex, width, height, minLine)
    }

    /** 只画底纹线条，不铺纸色（叠加在自定义背景图上时使用）。 */
    @Synchronized
    fun renderLines(
        canvas: Canvas,
        template: PageTemplate,
        backgroundColorHex: String,
        width: Int,
        height: Int,
        minLine: Float = 0f,
    ) {
        if (template == PageTemplate.BLANK) return

        dashedPaint.pathEffect = DashPathEffect(TemplateLayout.dashIntervals(width), 0f)
        for (shape in TemplateLayout.build(template, backgroundColorHex, width, height)) {
            when (shape) {
                is TemplateLine -> {
                    val p = if (shape.dashed) dashedPaint else linePaint
                    p.color = color(shape.color)
                    p.strokeWidth = maxOf(shape.width, minLine)
                    canvas.drawLine(shape.x1, shape.y1, shape.x2, shape.y2, p)
                }
                is TemplateRect -> {
                    linePaint.color = color(shape.color)
                    linePaint.strokeWidth = maxOf(shape.width, minLine)
                    canvas.drawRect(shape.left, shape.top, shape.right, shape.bottom, linePaint)
                }
                is TemplateDot -> {
                    fillPaint.color = color(shape.color)
                    canvas.drawCircle(shape.cx, shape.cy, maxOf(shape.r, minLine / 2f), fillPaint)
                }
            }
        }
    }

    private fun color(hex: String): Int = colorCache.getOrPut(hex) {
        try {
            Color.parseColor(hex)
        } catch (_: Exception) {
            Color.WHITE
        }
    }
}
