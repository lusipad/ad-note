package com.adnote.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import com.adnote.ink.StrokeGeometry
import com.adnote.model.Page
import com.adnote.model.Stroke

class InkCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var page: Page? = null
    private var backgroundBitmap: Bitmap? = null
    private var transientStroke: Stroke? = null

    private val paint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val renderPath = Path()
    private val srcRect = Rect()
    private val dstRect = Rect()

    fun setPage(page: Page) {
        this.page = page
        invalidate()
    }

    fun getPage(): Page? = page

    fun setBackgroundBitmap(bitmap: Bitmap?) {
        this.backgroundBitmap = bitmap
        invalidate()
    }

    fun getBackgroundBitmap(): Bitmap? = backgroundBitmap

    fun setTransientStroke(stroke: Stroke?) {
        this.transientStroke = stroke
        invalidate()
    }

    fun addStroke(stroke: Stroke) {
        this.transientStroke = null
        val p = page ?: return
        page = p.copy(strokes = p.strokes + stroke)
        invalidate()
    }

    fun eraseStrokes(strokeIds: Set<String>): Boolean {
        val p = page ?: return false
        if (strokeIds.isEmpty()) return false
        val newStrokes = p.strokes.filterNot { it.id in strokeIds }
        if (newStrokes.size != p.strokes.size) {
            page = p.copy(strokes = newStrokes)
            invalidate()
            return true
        }
        return false
    }

    fun undo(): Stroke? {
        val p = page ?: return null
        if (p.strokes.isEmpty()) return null
        val lastStroke = p.strokes.last()
        page = p.copy(strokes = p.strokes.dropLast(1))
        invalidate()
        return lastStroke
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. 绘制背景层：若有 PDF 页面底图则渲染底图，否则纯白底
        val bmp = backgroundBitmap
        if (bmp != null && !bmp.isRecycled) {
            srcRect.set(0, 0, bmp.width, bmp.height)
            dstRect.set(0, 0, width, height)
            canvas.drawBitmap(bmp, srcRect, dstRect, null)
        } else {
            canvas.drawColor(Color.WHITE)
        }

        // 2. 绘制上层手写笔迹涂层
        val currentStrokes = page?.strokes ?: emptyList()
        val allStrokes = if (transientStroke != null) currentStrokes + transientStroke!! else currentStrokes

        for (stroke in allStrokes) {
            val outline = StrokeGeometry.outline(stroke)
            if (outline.isNotEmpty()) {
                renderPath.reset()
                renderPath.moveTo(outline[0].x, outline[0].y)
                for (i in 1 until outline.size) {
                    renderPath.lineTo(outline[i].x, outline[i].y)
                }
                renderPath.close()
                canvas.drawPath(renderPath, paint)
            } else if (stroke.points.isNotEmpty()) {
                val pt = stroke.points[0]
                val r = StrokeGeometry.widthAt(stroke.width, pt.pressure) / 2f
                canvas.drawCircle(pt.x, pt.y, r, paint)
            }
        }
    }
}
