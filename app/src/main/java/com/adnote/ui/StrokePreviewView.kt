package com.adnote.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import com.adnote.model.InkPoint
import com.adnote.model.PaperPresets
import com.adnote.model.PenType
import com.adnote.model.Stroke
import kotlin.math.PI
import kotlin.math.sin

/** 画笔设置对话框顶部的笔迹预览：一条压感由轻到重再到轻的波浪线。 */
class StrokePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val painter = StrokePainter()
    private var pen: PenType = PenType.FOUNTAIN
    private var colorHex: String = "#000000"
    private var strokeWidth: Float = 3.5f
    private var paperHex: String = PaperPresets.WHITE.hex

    fun update(pen: PenType, color: String, width: Float, paper: String) {
        this.pen = pen
        this.colorHex = color
        this.strokeWidth = width
        this.paperHex = paper
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(StrokePainter.parseColor(PaperPresets.find(paperHex).hex))
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val margin = w * 0.08f
        val n = 60
        val points = (0..n).map { i ->
            val t = i / n.toFloat()
            InkPoint(
                x = margin + (w - 2 * margin) * t,
                y = h / 2f + sin(t * 2 * PI).toFloat() * h * 0.22f,
                pressure = sin(t * PI).toFloat(),
            )
        }
        painter.draw(canvas, Stroke(points = points, width = strokeWidth, color = colorHex, pen = pen))
    }
}
