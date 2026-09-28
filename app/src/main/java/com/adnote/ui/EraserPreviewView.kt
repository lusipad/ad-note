package com.adnote.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 橡皮大小预览：按屏幕上的实际大小画出橡皮的圆形范围。
 * 工具栏里的档位圆点用 [compact] 模式（固定按钮大小），当前档位用 [filled] 实心显示。
 */
class EraserPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    /** 圆的直径（像素）。 */
    var diameterPx: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    var filled: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** 工具栏档位按钮：固定大小，不随直径改变。 */
    var compact: Boolean = false

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0xFF4B5563.toInt()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF111827.toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (compact) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val w = getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        val h = max((56 * density).roundToInt(), (diameterPx + 16 * density).roundToInt())
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = diameterPx / 2f
        val cx = width / 2f
        val cy = height / 2f
        if (filled) canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, max(r, 1f), outline)
    }
}
