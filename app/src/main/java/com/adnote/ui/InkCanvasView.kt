package com.adnote.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.adnote.ink.StrokeGeometry
import com.adnote.model.InkPoint
import com.adnote.model.Page
import com.adnote.model.Stroke
import kotlin.math.abs

/**
 * 手写画布。
 *
 * 底纹/PDF 底图与已落笔的笔迹缓存在一张位图里，书写中只需在其上叠加当前笔画，
 * 笔迹再多也不会拖慢实时预览。另外负责绘制橡皮光标、套索轨迹与选区，
 * 并处理手指横滑翻页、拖动选区等不经过画笔通道的触摸。
 */
class InkCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 选区交互回调。 */
    interface SelectionListener {
        /** 选区被拖动了 (dx, dy)，手指/笔已抬起。 */
        fun onSelectionMoved(dx: Float, dy: Float)

        /** 在选区外点击，应取消选择。 */
        fun onSelectionDismissed()
    }

    private var page: Page? = null
    private var backgroundBitmap: Bitmap? = null
    private var transientStroke: Stroke? = null

    private var contentBitmap: Bitmap? = null
    private var contentCanvas: Canvas? = null
    private var contentDirty = true

    private var eraserX = 0f
    private var eraserY = 0f
    private var eraserRadius = 0f

    private var lassoPoints: List<InkPoint> = emptyList()

    private var selectedIds: Set<String> = emptySet()
    private var selectedStrokes: List<Stroke> = emptyList()
    private val selectionBounds = RectF()
    private var dragging = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragDx = 0f
    private var dragDy = 0f

    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeTracking = false

    /** 手指横滑翻页：+1 下一页，-1 上一页。 */
    var onSwipe: ((Int) -> Unit)? = null
    var selectionListener: SelectionListener? = null

    private val painter = StrokePainter()
    private val srcRect = Rect()
    private val dstRect = Rect()
    private val density = resources.displayMetrics.density

    private val cursorPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.parseColor("#6B7280")
        isAntiAlias = true
    }
    private val dashPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.parseColor("#374151")
        pathEffect = DashPathEffect(floatArrayOf(8f * density, 6f * density), 0f)
        isAntiAlias = true
    }
    private val overlayPath = Path()

    fun setPage(page: Page) {
        this.page = page
        transientStroke = null
        clearSelectionState()
        invalidateContent()
    }

    fun getPage(): Page? = page

    /** 替换当前页全部笔画（撤销、擦除、清空、移动选区后调用）。 */
    fun setStrokes(strokes: List<Stroke>) {
        val p = page ?: return
        page = p.copy(strokes = strokes)
        if (selectedIds.isNotEmpty()) refreshSelectedStrokes()
        invalidateContent()
    }

    fun setBackgroundBitmap(bitmap: Bitmap?) {
        this.backgroundBitmap = bitmap
        invalidateContent()
    }

    fun setTransientStroke(stroke: Stroke?) {
        this.transientStroke = stroke
        invalidate()
    }

    /** 落笔：增量画到缓存位图上，无需整页重绘。 */
    fun addStroke(stroke: Stroke) {
        transientStroke = null
        val p = page ?: return
        page = p.copy(strokes = p.strokes + stroke)
        val c = contentCanvas
        if (!contentDirty && c != null) painter.draw(c, stroke) else contentDirty = true
        invalidate()
    }

    /** 显示/隐藏橡皮光标。radius <= 0 表示隐藏。 */
    fun setEraserCursor(x: Float, y: Float, radius: Float) {
        eraserX = x; eraserY = y; eraserRadius = radius
        invalidate()
    }

    fun hideEraserCursor() {
        if (eraserRadius <= 0f) return
        eraserRadius = 0f
        invalidate()
    }

    fun setLassoPath(points: List<InkPoint>) {
        lassoPoints = points
        invalidate()
    }

    fun setSelection(ids: Set<String>) {
        selectedIds = ids
        dragDx = 0f; dragDy = 0f; dragging = false
        refreshSelectedStrokes()
        invalidateContent()
    }

    fun clearSelection() {
        if (selectedIds.isEmpty()) return
        clearSelectionState()
        invalidateContent()
    }

    val hasSelection: Boolean get() = selectedIds.isNotEmpty()

    private fun clearSelectionState() {
        selectedIds = emptySet()
        selectedStrokes = emptyList()
        dragging = false
        dragDx = 0f; dragDy = 0f
    }

    private fun refreshSelectedStrokes() {
        selectedStrokes = page?.strokes?.filter { it.id in selectedIds }.orEmpty()
        val b = StrokeGeometry.bounds(selectedStrokes)
        if (b == null) {
            selectionBounds.setEmpty()
        } else {
            val pad = 10f * density
            selectionBounds.set(b[0] - pad, b[1] - pad, b[2] + pad, b[3] + pad)
        }
    }

    private fun invalidateContent() {
        contentDirty = true
        invalidate()
    }

    private fun ensureContent() {
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        var bmp = contentBitmap
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            contentBitmap = bmp
            contentCanvas = Canvas(bmp)
            contentDirty = true
        }
        if (!contentDirty) return
        val c = contentCanvas ?: return

        // 1. 背景层：PDF 底图，或按模板绘制纸张底纹
        val bg = backgroundBitmap
        val p = page
        if (bg != null && !bg.isRecycled) {
            c.drawColor(Color.WHITE)
            srcRect.set(0, 0, bg.width, bg.height)
            dstRect.set(0, 0, w, h)
            c.drawBitmap(bg, srcRect, dstRect, null)
        } else if (p != null) {
            PageTemplateRenderer.render(c, p.template, p.backgroundColor, w, h)
        } else {
            c.drawColor(Color.WHITE)
        }

        // 2. 笔迹层（选中的笔画单独画在最上层，便于拖动）
        p?.strokes?.forEach { s -> if (s.id !in selectedIds) painter.draw(c, s) }
        contentDirty = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        ensureContent()
        contentBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        if (selectedStrokes.isNotEmpty()) {
            canvas.save()
            canvas.translate(dragDx, dragDy)
            painter.drawAll(canvas, selectedStrokes)
            canvas.drawRect(selectionBounds, dashPaint)
            canvas.restore()
        }

        transientStroke?.let { painter.draw(canvas, it) }

        if (lassoPoints.size > 1) {
            overlayPath.reset()
            overlayPath.moveTo(lassoPoints[0].x, lassoPoints[0].y)
            for (i in 1 until lassoPoints.size) overlayPath.lineTo(lassoPoints[i].x, lassoPoints[i].y)
            canvas.drawPath(overlayPath, dashPaint)
        }

        if (eraserRadius > 0f) {
            canvas.drawCircle(eraserX, eraserY, eraserRadius, cursorPaint)
        }
    }

    /**
     * 只有画笔通道没有消费的触摸才会到这里：
     * 开启防误触时的手指、文石设备上的手指，以及有选区时（画笔通道已暂停）的笔和手指。
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (selectedIds.isNotEmpty()) return handleSelectionTouch(event)
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return super.onTouchEvent(event)
        return handleSwipe(event)
    }

    private fun handleSelectionTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (selectionBounds.contains(event.x, event.y)) {
                    dragging = true
                    dragStartX = event.x; dragStartY = event.y
                } else {
                    dragging = false
                    selectionListener?.onSelectionDismissed()
                }
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                dragDx = event.x - dragStartX
                dragDy = event.y - dragStartY
                invalidate()
            }
            MotionEvent.ACTION_UP -> if (dragging) {
                dragging = false
                val dx = event.x - dragStartX
                val dy = event.y - dragStartY
                // 回调里会用平移后的笔画替换页面，之后拖动偏移归零
                if (abs(dx) > 2f || abs(dy) > 2f) selectionListener?.onSelectionMoved(dx, dy)
                dragDx = 0f; dragDy = 0f
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                dragDx = 0f; dragDy = 0f
                invalidate()
            }
        }
        return true
    }

    private fun handleSwipe(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeStartX = event.x; swipeStartY = event.y
                swipeTracking = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> swipeTracking = false
            MotionEvent.ACTION_UP -> if (swipeTracking) {
                swipeTracking = false
                val dx = event.x - swipeStartX
                val dy = event.y - swipeStartY
                if (abs(dx) > SWIPE_MIN_DP * density && abs(dx) > abs(dy) * 1.5f) {
                    onSwipe?.invoke(if (dx < 0) 1 else -1)
                }
            }
            MotionEvent.ACTION_CANCEL -> swipeTracking = false
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        contentBitmap?.recycle()
        contentBitmap = null
        contentCanvas = null
        contentDirty = true
    }

    companion object {
        private const val SWIPE_MIN_DP = 80f
    }
}
