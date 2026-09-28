package com.adnote.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.adnote.ink.Affine
import com.adnote.ink.Ruler
import com.adnote.ink.Selection
import com.adnote.ink.SelectionOps
import com.adnote.model.InkPoint
import com.adnote.model.Page
import com.adnote.model.Stroke
import com.adnote.model.Viewport
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * 手写画布。
 *
 * - 内容存储在页面坐标系，经 [Viewport] 映射到屏幕：页面按宽度铺满，可双指缩放、拖动平移；
 * - 当前视口下的整页渲染结果缓存在一张位图里，书写时只叠加正在写的一笔；
 *   缩放/平移过程中先把缓存位图按比例变换显示，手势结束后再按新比例重新渲染，保证清晰；
 * - 绘制橡皮/悬停光标、套索轨迹、直尺、选区（含缩放/旋转手柄）；
 * - 处理画笔通道没有消费的触摸：手指平移缩放、横滑翻页、单击链接、双击复位、
 *   两指/三指点按（撤销/重做）、拖动直尺、拖动/缩放/旋转选区。
 */
class InkCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        /** 选区被拖动/缩放/旋转，手指或笔已抬起。 */
        fun onSelectionTransformed(transform: Affine) {}

        /** 在选区外点击，应取消选择。 */
        fun onSelectionDismissed() {}

        /** 手指横滑翻页：+1 下一页，-1 上一页。 */
        fun onSwipe(direction: Int) {}

        /** 手指单击（页面坐标），用于打开链接。 */
        fun onTap(pageX: Float, pageY: Float) {}

        /** 多指点按：2 = 撤销，3 = 重做。 */
        fun onMultiFingerTap(fingers: Int) {}

        /** 缩放/平移结束后的新视口。 */
        fun onViewportChanged(viewport: Viewport) {}

        /** 直尺被移动或旋转。 */
        fun onRulerMoved(ruler: Ruler) {}
    }

    var listener: Listener? = null

    private var page: Page? = null
    private var pdfBackground: Bitmap? = null
    private var renderer = PageRenderer(null)

    var viewport: Viewport = Viewport(1, 1, 1, 1)
        private set

    private var cache: Bitmap? = null
    private var cacheCanvas: Canvas? = null
    private var cacheViewport: Viewport? = null
    private var cacheDirty = true
    private var gesturing = false

    private var transientStroke: Stroke? = null
    private var lassoPoints: List<InkPoint> = emptyList()
    private var ruler: Ruler? = null

    private var cursorX = 0f
    private var cursorY = 0f
    private var cursorRadius = 0f
    private var hoverX = 0f
    private var hoverY = 0f
    private var hoverRadius = 0f

    private var selection: Selection = Selection.EMPTY
    private var selectionPage: Page? = null
    private var previewPage: Page? = null
    private var previewTransform: Affine? = null

    private val density = resources.displayMetrics.density
    private val pageMatrix = Matrix()
    private val cacheMatrix = Matrix()

    private val overlayStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#374151")
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#111827")
    }
    private val rulerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#33A1A1AA")
    }
    private val overlayPath = Path()

    // region 公共接口

    fun setAssets(assets: BitmapAssets?) {
        renderer = PageRenderer(assets)
        invalidateContent()
    }

    /**
     * 切换页面。[resetView] 为 true（翻页）时缩放复位；同一页内容更新请用 [updatePage]。
     */
    fun setPage(page: Page, resetView: Boolean = true) {
        val old = this.page
        this.page = page
        transientStroke = null
        lassoPoints = emptyList()
        clearSelectionState()
        if (resetView || old == null || old.width != page.width || old.height != page.height) {
            viewport = Viewport(max(width, 1), max(height, 1), page.width, page.height).clamped()
        }
        invalidateContent()
    }

    fun getPage(): Page? = page

    /** 替换当前页内容（撤销、擦除、编辑文字等），保持缩放与选区。 */
    fun updatePage(page: Page) {
        val old = this.page
        this.page = page
        if (old != null && (old.width != page.width || old.height != page.height)) {
            viewport = viewport.copy(pageW = page.width, pageH = page.height).clamped()
        }
        if (!selection.isEmpty) refreshSelectionPage()
        invalidateContent()
    }

    fun setStrokes(strokes: List<Stroke>) {
        page?.let { updatePage(it.copy(strokes = strokes)) }
    }

    fun setBackgroundBitmap(bitmap: Bitmap?) {
        pdfBackground = bitmap
        invalidateContent()
    }

    /** 落笔：若新笔画在最上层图层，增量画到缓存上，无需整页重绘。 */
    fun addStroke(stroke: Stroke) {
        transientStroke = null
        val p = page ?: return
        page = p.copy(strokes = p.strokes + stroke)
        val c = cacheCanvas
        val topLayer = p.layers.lastOrNull { it.visible }?.id
        if (!cacheDirty && c != null && cacheViewport == viewport && stroke.layer == topLayer) {
            c.save()
            c.concat(matrixFor(viewport, pageMatrix))
            c.clipRect(0f, 0f, p.width.toFloat(), p.height.toFloat())
            renderer.drawStroke(c, stroke)
            c.restore()
        } else {
            cacheDirty = true
        }
        invalidate()
    }

    fun setTransientStroke(stroke: Stroke?) {
        transientStroke = stroke
        invalidate()
    }

    /** 橡皮光标（页面坐标与半径）。 */
    fun setEraserCursor(x: Float, y: Float, radius: Float) {
        cursorX = x; cursorY = y; cursorRadius = radius
        invalidate()
    }

    fun hideEraserCursor() {
        if (cursorRadius <= 0f) return
        cursorRadius = 0f
        invalidate()
    }

    /** 笔尖悬停位置（页面坐标），radius 为笔宽或橡皮半径。 */
    fun setHover(x: Float, y: Float, radius: Float) {
        hoverX = x; hoverY = y; hoverRadius = max(radius, 3f / viewport.scale)
        invalidate()
    }

    fun clearHover() {
        if (hoverRadius <= 0f) return
        hoverRadius = 0f
        invalidate()
    }

    fun setLassoPath(points: List<InkPoint>) {
        lassoPoints = points
        invalidate()
    }

    fun setRuler(r: Ruler?) {
        ruler = r
        invalidate()
    }

    fun getRuler(): Ruler? = ruler

    fun setSelection(sel: Selection) {
        selection = sel
        previewTransform = null
        previewPage = null
        refreshSelectionPage()
        invalidateContent()
    }

    fun clearSelection() {
        if (selection.isEmpty) return
        clearSelectionState()
        invalidateContent()
    }

    val hasSelection: Boolean get() = !selection.isEmpty

    fun resetZoom() {
        applyViewport(viewport.reset(), notify = true)
    }

    /** 视图坐标 → 页面坐标。 */
    fun toPage(points: List<InkPoint>): List<InkPoint> {
        val vp = viewport
        return points.map { it.copy(x = vp.toPageX(it.x), y = vp.toPageY(it.y)) }
    }

    /** 单指按在直尺上时由画布处理（拖动直尺），而不是书写。 */
    fun wantsFinger(e: MotionEvent): Boolean =
        ruler?.contains(viewport.toPageX(e.x), viewport.toPageY(e.y)) == true

    fun toPageX(vx: Float) = viewport.toPageX(vx)
    fun toPageY(vy: Float) = viewport.toPageY(vy)

    // endregion

    private fun clearSelectionState() {
        selection = Selection.EMPTY
        selectionPage = null
        previewPage = null
        previewTransform = null
    }

    private fun refreshSelectionPage() {
        val p = page ?: return
        selectionPage = p.copy(
            strokes = p.strokes.filter { it.id in selection.strokes },
            texts = p.texts.filter { it.id in selection.texts },
            images = p.images.filter { it.id in selection.images },
        )
        previewPage = null
    }

    private fun invalidateContent() {
        cacheDirty = true
        invalidate()
    }

    private fun applyViewport(vp: Viewport, notify: Boolean) {
        if (vp == viewport) return
        viewport = vp
        if (notify) listener?.onViewportChanged(vp)
        invalidate()
    }

    private fun matrixFor(vp: Viewport, out: Matrix): Matrix {
        out.reset()
        out.setScale(vp.scale, vp.scale)
        out.postTranslate(-vp.panX, -vp.panY)
        return out
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val p = page
        viewport = if (p != null) Viewport(w, h, p.width, p.height).clamped() else Viewport(w, h, w, h)
        invalidateContent()
    }

    private fun ensureCache() {
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        var bmp = cache
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            cache = bmp
            cacheCanvas = Canvas(bmp)
            cacheDirty = true
        }
        if (!cacheDirty && (gesturing || cacheViewport == viewport)) return
        val c = cacheCanvas ?: return
        c.drawColor(OUTSIDE_COLOR)
        val p = page
        if (p != null) {
            c.save()
            c.concat(matrixFor(viewport, pageMatrix))
            c.clipRect(0f, 0f, p.width.toFloat(), p.height.toFloat())
            renderer.drawPage(c, p, pdfBackground, hidden = selection)
            c.restore()
        }
        cacheViewport = viewport
        cacheDirty = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        ensureCache()
        val bmp = cache ?: return
        val cv = cacheViewport
        if (cv == null || cv == viewport) {
            canvas.drawBitmap(bmp, 0f, 0f, null)
        } else {
            // 手势进行中：把旧比例的缓存变换到当前视口，手势结束后重新渲染
            canvas.drawColor(OUTSIDE_COLOR)
            val k = viewport.scale / cv.scale
            cacheMatrix.reset()
            cacheMatrix.setScale(k, k)
            cacheMatrix.postTranslate(cv.panX * k - viewport.panX, cv.panY * k - viewport.panY)
            canvas.drawBitmap(bmp, cacheMatrix, null)
        }

        val scale = viewport.scale
        canvas.save()
        canvas.concat(matrixFor(viewport, pageMatrix))

        val selPage = previewPage ?: selectionPage
        if (selPage != null && !selection.isEmpty) {
            renderer.drawContent(canvas, selPage)
            SelectionOps.bounds(selPage, selection)?.let { drawSelectionFrame(canvas, it, scale) }
        }

        transientStroke?.let { renderer.drawStroke(canvas, it) }

        if (lassoPoints.size > 1) {
            setDashed(overlayStroke, scale)
            overlayPath.reset()
            overlayPath.moveTo(lassoPoints[0].x, lassoPoints[0].y)
            for (i in 1 until lassoPoints.size) overlayPath.lineTo(lassoPoints[i].x, lassoPoints[i].y)
            canvas.drawPath(overlayPath, overlayStroke)
        }

        ruler?.let { drawRuler(canvas, it, scale) }

        overlayStroke.pathEffect = null
        overlayStroke.strokeWidth = 1.5f * density / scale
        if (cursorRadius > 0f) canvas.drawCircle(cursorX, cursorY, cursorRadius, overlayStroke)
        if (hoverRadius > 0f) canvas.drawCircle(hoverX, hoverY, hoverRadius, overlayStroke)

        canvas.restore()
    }

    private fun setDashed(p: Paint, scale: Float) {
        p.strokeWidth = 1.5f * density / scale
        p.pathEffect = DashPathEffect(floatArrayOf(8f * density / scale, 6f * density / scale), 0f)
    }

    private fun drawSelectionFrame(canvas: Canvas, b: FloatArray, scale: Float) {
        val pad = 8f * density / scale
        setDashed(overlayStroke, scale)
        canvas.drawRect(b[0] - pad, b[1] - pad, b[2] + pad, b[3] + pad, overlayStroke)
        overlayStroke.pathEffect = null
        val r = HANDLE_DP * density / scale / 2f
        // 右下角缩放手柄
        canvas.drawRect(b[2] + pad - r, b[3] + pad - r, b[2] + pad + r, b[3] + pad + r, handleFill)
        // 顶部旋转手柄
        val cx = (b[0] + b[2]) / 2f
        val ry = b[1] - pad - ROTATE_OFFSET_DP * density / scale
        canvas.drawLine(cx, b[1] - pad, cx, ry, overlayStroke)
        canvas.drawCircle(cx, ry, r, handleFill)
    }

    private fun drawRuler(canvas: Canvas, r: Ruler, scale: Float) {
        val c = r.corners()
        overlayPath.reset()
        overlayPath.moveTo(c[0].first, c[0].second)
        for (i in 1 until 4) overlayPath.lineTo(c[i].first, c[i].second)
        overlayPath.close()
        canvas.drawPath(overlayPath, rulerFill)
        overlayStroke.pathEffect = null
        overlayStroke.strokeWidth = 1.5f * density / scale
        canvas.drawPath(overlayPath, overlayStroke)
        // 刻度：沿上边缘每 20 像素一格，每 100 像素一长格
        val ux = (c[1].first - c[0].first) / r.length
        val uy = (c[1].second - c[0].second) / r.length
        val nx = -uy; val ny = ux
        var a = 0f
        var i = 0
        overlayStroke.strokeWidth = 1f * density / scale
        while (a <= r.length) {
            val len = if (i % 5 == 0) r.width * 0.3f else r.width * 0.15f
            val x = c[0].first + ux * a; val y = c[0].second + uy * a
            canvas.drawLine(x, y, x + nx * len, y + ny * len, overlayStroke)
            a += 20f; i++
        }
    }

    // region 触摸

    private enum class Mode { NONE, PAN, PINCH, RULER, RULER_ROTATE, SEL_MOVE, SEL_SCALE, SEL_ROTATE }

    private var mode = Mode.NONE
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var maxPointers = 0
    private var travel = 0f
    private var pinchStartDist = 1f
    private var pinchStartVp: Viewport? = null
    private var focusStartX = 0f
    private var focusStartY = 0f
    private var lastAngle = 0f
    private var lastTapTime = 0L

    /** 这一次触摸被判定为手掌/书写时的误触，整次忽略直到抬起。 */
    private var rejected = false
    /** 单指移动超过触摸阈值后才开始平移，避免手指微小抖动反复重绘（墨水屏上就是不停刷新）。 */
    private var panStarted = false
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop * 2f
    private var penDown = false
    private var lastPenTime = 0L

    private var selStartX = 0f
    private var selStartY = 0f
    private var selPivotX = 0f
    private var selPivotY = 0f

    /** 笔按下/抬起。笔在屏幕上时（以及抬起后片刻）手指和手掌的触摸一律忽略。 */
    fun setPenDown(down: Boolean) {
        // 只有真正按下过笔才记时间：手指书写被取消时也会走到「抬起」，不能把之后的双指手势当成误触
        if (!down && !penDown) return
        penDown = down
        lastPenTime = android.os.SystemClock.uptimeMillis()
    }

    /** 笔尖悬停在屏幕上方：说明手正准备书写，同样屏蔽手掌。 */
    fun notePenNear() {
        lastPenTime = android.os.SystemClock.uptimeMillis()
    }

    private fun penRecentlyActive(): Boolean =
        penDown || android.os.SystemClock.uptimeMillis() - lastPenTime < PALM_WINDOW_MS

    /** 接触面积明显大于指尖，判定为手掌（部分设备不上报面积，此时只靠笔的状态判断）。 */
    private fun isPalm(e: MotionEvent): Boolean {
        val limit = PALM_DP * density
        return (0 until e.pointerCount).any { e.getTouchMajor(it) > limit }
    }

    /**
     * 只有画笔通道没有消费的触摸才会到这里：防误触模式或文石设备上的手指、
     * 有选区时（画笔通道已暂停）的笔和手指，以及画笔通道转交过来的多指手势。
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 笔只用于操作选区；其余情况笔的事件由画笔通道负责（文石直绘时视图也可能收到笔事件，不能拿来平移）
        val tool = event.getToolType(0)
        val isStylus = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
        if (isStylus && selection.isEmpty) return false
        if (!isStylus && !selection.isEmpty && event.actionMasked == MotionEvent.ACTION_DOWN &&
            (isPalm(event) || penRecentlyActive())
        ) {
            // 书写时搭在屏幕上的手掌不能把选区取消掉
            rejected = true
        }
        if (!isStylus && rejected && mode == Mode.NONE) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) rejected = false
            return true
        }
        if (!selection.isEmpty && event.pointerCount == 1 &&
            (mode == Mode.NONE || mode == Mode.SEL_MOVE || mode == Mode.SEL_SCALE || mode == Mode.SEL_ROTATE)
        ) {
            return handleSelection(event)
        }
        return handleGesture(event)
    }

    /** 手指手势。也供画笔通道在检测到多指时转交（可以从 POINTER_DOWN 中途开始）。 */
    fun handleGesture(e: MotionEvent): Boolean {
        val action = e.actionMasked
        if (action == MotionEvent.ACTION_DOWN) rejected = false
        // 手掌，或者笔正在/刚刚书写：整次触摸作废，不平移、不缩放、不翻页、不触发多指撤销
        if (!rejected && (isPalm(e) || penRecentlyActive())) rejectGesture()
        if (rejected) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                rejected = false
                mode = Mode.NONE
            }
            return true
        }
        when (action) {
            MotionEvent.ACTION_DOWN -> startGesture(e)
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode == Mode.NONE || mode.isSelection()) startGesture(e)
                maxPointers = max(maxPointers, e.pointerCount)
                if (e.pointerCount == 2) {
                    if (mode == Mode.RULER) {
                        mode = Mode.RULER_ROTATE
                        lastAngle = angle(e)
                    } else {
                        mode = Mode.PINCH
                        previewPage = null
                        pinchStartDist = max(distance(e), 1f)
                        pinchStartVp = viewport
                        focusStartX = focusX(e); focusStartY = focusY(e)
                        gesturing = true
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val x = e.getX(0); val y = e.getY(0)
                travel += abs(x - lastX) + abs(y - lastY)
                when (mode) {
                    Mode.PAN -> if (e.pointerCount == 1 && (panStarted || hypot(x - downX, y - downY) > touchSlop)) {
                        panStarted = true
                        val vp = viewport
                        val next = when {
                            vp.isZoomed -> vp.panBy(x - lastX, y - lastY)
                            vp.scrollsVertically -> vp.panBy(0f, y - lastY)
                            else -> vp
                        }
                        if (next != vp) { gesturing = true; applyViewport(next, notify = false) }
                    }
                    Mode.PINCH -> if (e.pointerCount >= 2) {
                        val start = pinchStartVp ?: viewport
                        val next = start.zoomBy(distance(e) / pinchStartDist, focusStartX, focusStartY)
                            .panBy(focusX(e) - focusStartX, focusY(e) - focusStartY)
                        applyViewport(next, notify = false)
                    }
                    Mode.RULER -> ruler?.let {
                        ruler = it.moved((x - lastX) / viewport.scale, (y - lastY) / viewport.scale)
                        invalidate()
                    }
                    Mode.RULER_ROTATE -> if (e.pointerCount >= 2) ruler?.let {
                        val a = angle(e)
                        ruler = it.rotated(a - lastAngle)
                        lastAngle = a
                        invalidate()
                    }
                    else -> Unit
                }
                lastX = x; lastY = y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 剩下的手指继续平移时，从它当前的位置开始，避免跳动
                val remaining = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(remaining); lastY = e.getY(remaining)
                if (mode == Mode.PINCH && e.pointerCount - 1 < 2) mode = Mode.PAN
                if (mode == Mode.RULER_ROTATE && e.pointerCount - 1 < 2) mode = Mode.RULER
            }
            MotionEvent.ACTION_UP -> {
                finishGesture(e)
                mode = Mode.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                endViewportGesture()
                mode = Mode.NONE
            }
        }
        return true
    }

    private fun Mode.isSelection() = this == Mode.SEL_MOVE || this == Mode.SEL_SCALE || this == Mode.SEL_ROTATE

    private fun startGesture(e: MotionEvent) {
        downTime = e.eventTime
        downX = e.getX(0); downY = e.getY(0)
        lastX = downX; lastY = downY
        travel = 0f
        panStarted = false
        maxPointers = e.pointerCount
        val r = ruler
        mode = if (r != null && r.contains(viewport.toPageX(downX), viewport.toPageY(downY))) Mode.RULER else Mode.PAN
    }

    private fun finishGesture(e: MotionEvent) {
        val duration = e.eventTime - downTime
        val tapSlop = 12f * density
        if (maxPointers >= 2) {
            if (duration < MULTI_TAP_MS && travel < 40f * density && (mode == Mode.PAN || mode == Mode.PINCH || mode == Mode.NONE)) {
                pinchStartVp?.let { applyViewport(it, notify = false) }
                listener?.onMultiFingerTap(maxPointers)
            }
        } else if (mode == Mode.PAN) {
            val dx = e.x - downX; val dy = e.y - downY
            if (!viewport.isZoomed && abs(dx) > SWIPE_MIN_DP * density && abs(dx) > abs(dy) * 1.5f) {
                listener?.onSwipe(if (dx < 0) 1 else -1)
            } else if (abs(dx) < tapSlop && abs(dy) < tapSlop && duration < TAP_MS) {
                if (e.eventTime - lastTapTime < DOUBLE_TAP_MS && viewport.isZoomed) {
                    applyViewport(viewport.reset(), notify = true)
                    lastTapTime = 0L
                } else {
                    listener?.onTap(viewport.toPageX(e.x), viewport.toPageY(e.y))
                    lastTapTime = e.eventTime
                }
            }
        }
        if (mode == Mode.RULER || mode == Mode.RULER_ROTATE) ruler?.let { listener?.onRulerMoved(it) }
        endViewportGesture()
    }

    /** 放弃当前手势：视口停在当前位置，按新比例重新渲染一次。 */
    private fun rejectGesture() {
        rejected = true
        endViewportGesture()
        mode = Mode.NONE
    }

    private fun endViewportGesture() {
        pinchStartVp = null
        if (gesturing) {
            gesturing = false
            listener?.onViewportChanged(viewport)
            invalidate()
        }
    }

    private fun handleSelection(e: MotionEvent): Boolean {
        val px = viewport.toPageX(e.x); val py = viewport.toPageY(e.y)
        val base = selectionPage ?: return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val b = SelectionOps.bounds(base, selection) ?: return true
                val scale = viewport.scale
                val pad = 8f * density / scale
                val hit = HANDLE_DP * density / scale
                val cx = (b[0] + b[2]) / 2f
                val rotY = b[1] - pad - ROTATE_OFFSET_DP * density / scale
                selStartX = px; selStartY = py
                mode = when {
                    hypot(px - cx, py - rotY) < hit -> {
                        selPivotX = cx; selPivotY = (b[1] + b[3]) / 2f
                        Mode.SEL_ROTATE
                    }
                    hypot(px - (b[2] + pad), py - (b[3] + pad)) < hit -> {
                        selPivotX = b[0]; selPivotY = b[1]
                        Mode.SEL_SCALE
                    }
                    px in b[0] - pad..b[2] + pad && py in b[1] - pad..b[3] + pad -> Mode.SEL_MOVE
                    else -> {
                        listener?.onSelectionDismissed()
                        Mode.NONE
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val t = selectionTransform(px, py) ?: return true
                previewTransform = t
                previewPage = SelectionOps.transform(base, selection, t)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val t = selectionTransform(px, py)
                val moved = hypot(px - selStartX, py - selStartY) > 2f / viewport.scale
                previewTransform = null
                if (t != null && moved) {
                    // 回调里会用变换后的内容更新页面，并重新 setSelection
                    listener?.onSelectionTransformed(t)
                } else {
                    previewPage = null
                    invalidate()
                }
                mode = Mode.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                previewTransform = null
                previewPage = null
                mode = Mode.NONE
                invalidate()
            }
        }
        return true
    }

    private fun selectionTransform(px: Float, py: Float): Affine? = when (mode) {
        Mode.SEL_MOVE -> Affine.translate(px - selStartX, py - selStartY)
        Mode.SEL_SCALE -> {
            val d0 = hypot(selStartX - selPivotX, selStartY - selPivotY)
            val d1 = hypot(px - selPivotX, py - selPivotY)
            if (d0 < 1f) null else Affine.scale((d1 / d0).coerceIn(0.1f, 10f), selPivotX, selPivotY)
        }
        Mode.SEL_ROTATE -> {
            val a0 = atan2(selStartY - selPivotY, selStartX - selPivotX)
            val a1 = atan2(py - selPivotY, px - selPivotX)
            Affine.rotate(a1 - a0, selPivotX, selPivotY)
        }
        else -> null
    }

    private fun distance(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    private fun angle(e: MotionEvent) = atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0))
    private fun focusX(e: MotionEvent): Float = (0 until e.pointerCount).map { e.getX(it) }.average().toFloat()
    private fun focusY(e: MotionEvent): Float = (0 until e.pointerCount).map { e.getY(it) }.average().toFloat()

    // endregion

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cache?.recycle()
        cache = null
        cacheCanvas = null
        cacheDirty = true
    }

    companion object {
        private const val SWIPE_MIN_DP = 80f
        /** 接触长轴超过这个尺寸（dp）视为手掌。 */
        private const val PALM_DP = 28f
        /** 笔抬起后这么久之内的手指触摸视为手掌误触。 */
        private const val PALM_WINDOW_MS = 600L
        private const val TAP_MS = 300L
        private const val DOUBLE_TAP_MS = 350L
        private const val MULTI_TAP_MS = 350L
        private const val HANDLE_DP = 22f
        private const val ROTATE_OFFSET_DP = 28f
        private val OUTSIDE_COLOR = Color.parseColor("#E5E7EB")
    }
}
