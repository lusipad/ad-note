package com.adnote.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
    private val paint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val renderPath = Path()

    fun setPage(page: Page) {
        this.page = page
        invalidate()
    }

    fun getPage(): Page? = page

    fun addStroke(stroke: Stroke) {
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
        canvas.drawColor(Color.WHITE)

        val currentStrokes = page?.strokes ?: return

        for (stroke in currentStrokes) {
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
