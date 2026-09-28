package com.adnote.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.adnote.export.SvgExporter
import com.adnote.ink.Selection
import com.adnote.ink.TextLayout
import com.adnote.model.ImageItem
import com.adnote.model.Page
import com.adnote.model.PaperPresets
import com.adnote.model.TextBox
import com.adnote.model.layerContents

/**
 * 在页面坐标系下绘制一整页：纸色 → PDF 底图或自定义背景 → 底纹 → 各图层（图片、笔画、文字）。
 * 画布、缩略图、PDF/PNG 导出共用，保证处处一致。每个线程使用自己的实例。
 */
class PageRenderer(private val assets: BitmapAssets?) {

    private val strokes = StrokePainter()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val srcRect = Rect()
    private val dstRect = RectF()

    /**
     * @param pdfBackground PDF 笔记当前页的渲染图（铺满页面）
     * @param hidden 不绘制的选区内容（拖动选区时单独绘制在上层）
     * @param minLine 底纹最细线宽（缩略图用）
     */
    fun drawPage(
        canvas: Canvas,
        page: Page,
        pdfBackground: Bitmap? = null,
        hidden: Selection = Selection.EMPTY,
        minLine: Float = 0f,
    ) {
        drawBackground(canvas, page, pdfBackground, minLine)
        drawContent(canvas, page, hidden)
    }

    fun drawBackground(canvas: Canvas, page: Page, pdfBackground: Bitmap? = null, minLine: Float = 0f) {
        val w = page.width.toFloat(); val h = page.height.toFloat()
        if (pdfBackground != null && !pdfBackground.isRecycled) {
            canvas.drawColor(Color.WHITE)
            dstRect.set(0f, 0f, w, h)
            canvas.drawBitmap(pdfBackground, null, dstRect, bitmapPaint)
            return
        }
        val paper = PaperPresets.find(page.backgroundColor)
        val bg = page.backgroundImage?.let { assets?.get(it) }
        if (bg == null) {
            PageTemplateRenderer.render(canvas, page.template, page.backgroundColor, page.width, page.height, minLine)
            return
        }
        canvas.drawColor(StrokePainter.parseColor(paper.hex))
        // 居中裁切铺满页面（与 SVG 的 xMidYMid slice 一致）
        val scale = maxOf(w / bg.width, h / bg.height)
        val sw = (w / scale).toInt(); val sh = (h / scale).toInt()
        val sx = (bg.width - sw) / 2; val sy = (bg.height - sh) / 2
        srcRect.set(sx, sy, sx + sw, sy + sh)
        dstRect.set(0f, 0f, w, h)
        canvas.drawBitmap(bg, srcRect, dstRect, bitmapPaint)
        PageTemplateRenderer.renderLines(canvas, page.template, page.backgroundColor, page.width, page.height, minLine)
    }

    fun drawContent(canvas: Canvas, page: Page, hidden: Selection = Selection.EMPTY) {
        for (c in page.layerContents()) {
            c.images.forEach { if (it.id !in hidden.images) drawImage(canvas, it) }
            val (highlighters, normalStrokes) = c.strokes.partition { it.pen == com.adnote.model.PenType.HIGHLIGHTER }
            highlighters.forEach { if (it.id !in hidden.strokes) strokes.draw(canvas, it) }
            normalStrokes.forEach { if (it.id !in hidden.strokes) strokes.draw(canvas, it) }
            c.texts.forEach { if (it.id !in hidden.texts) drawText(canvas, it, page.width) }
        }
    }

    fun drawStroke(canvas: Canvas, stroke: com.adnote.model.Stroke) = strokes.draw(canvas, stroke)

    fun drawImage(canvas: Canvas, img: ImageItem) {
        val bmp = assets?.get(img.path)
        dstRect.set(img.x, img.y, img.x + img.width, img.y + img.height)
        if (bmp == null) {
            // 图片文件丢失：画一个占位框
            textPaint.style = Paint.Style.STROKE
            textPaint.color = Color.GRAY
            textPaint.strokeWidth = 2f
            canvas.drawRect(dstRect, textPaint)
            textPaint.style = Paint.Style.FILL
            return
        }
        canvas.drawBitmap(bmp, null, dstRect, bitmapPaint)
    }

    fun drawText(canvas: Canvas, box: TextBox, pageWidth: Int) {
        textPaint.style = Paint.Style.FILL
        textPaint.textSize = box.size
        textPaint.color = StrokePainter.parseColor(box.color)
        textPaint.isUnderlineText = box.linkPageId != null
        val lines = TextLayout.lines(box, pageWidth) { textPaint.measureText(it) }
        lines.forEachIndexed { i, line ->
            val baseline = box.y + box.size * (i * TextLayout.LINE_HEIGHT + SvgExporter.BASELINE)
            canvas.drawText(line, box.x, baseline, textPaint)
        }
    }

    /** 测量文字宽度（与 [drawText] 使用同一字号设置）。 */
    fun measure(box: TextBox): (String) -> Float {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = box.size }
        return { p.measureText(it) }
    }
}
