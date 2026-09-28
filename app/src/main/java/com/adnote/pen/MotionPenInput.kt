package com.adnote.pen

import android.graphics.Rect
import android.os.Build
import android.view.MotionEvent
import android.view.MotionPredictor
import android.view.View
import com.adnote.model.InkPoint
import com.adnote.model.StylusButtonAction

/**
 * 标准触控通道：小米、vivo 等普通平板以及没有厂商 SDK 的墨水屏。
 *
 * - 采集 120/144Hz 历史点、压感与笔身倾角；
 * - Android 11+ 请求非缓冲事件分发，Android 14+ 使用系统运动预测补齐预览末端，降低视觉延迟；
 * - 笔尖悬停时回调位置；笔尾橡皮始终擦除，笔身按键可配置；
 * - 手指书写时如果第二根手指落下，取消当前笔画并把手势转交给画布（缩放、多指点按）。
 */
class MotionPenInput(
    var stylusOnly: Boolean = false,
) : PenInput {

    override val name: String
        get() = if (stylusOnly) "标准触控通道 (仅手写笔·防误触)" else "标准触控通道 (笔与手指均可)"

    private enum class Kind { DRAW, ERASE, ALT }

    private var view: View? = null
    private var listener: PenInputListener? = null
    private var isEnabled: Boolean = true
    private var gestureDelegate: ((MotionEvent) -> Boolean)? = null
    private var interceptFinger: (MotionEvent) -> Boolean = { false }
    private var buttonAction = StylusButtonAction.ERASER
    private var predictionEnabled = true
    private var predictor: Any? = null

    private val currentPoints = ArrayList<InkPoint>()
    private var kind = Kind.DRAW
    private var fingerStroke = false
    private var delegating = false

    override fun attach(view: View, limitRect: Rect, listener: PenInputListener) {
        this.view = view
        this.listener = listener
        if (Build.VERSION.SDK_INT >= 34) predictor = MotionPredictor(view.context)

        view.setOnTouchListener { _, event -> handleTouch(view, event) }
        view.setOnHoverListener { _, event ->
            if (!isEnabled || event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) return@setOnHoverListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE ->
                    listener.onHover(event.x, event.y, event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER)
                MotionEvent.ACTION_HOVER_EXIT -> listener.onHoverExit()
            }
            true
        }
    }

    private fun handleTouch(view: View, event: MotionEvent): Boolean {
        val listener = listener ?: return false

        // 多指手势转交画布，直到所有手指抬起
        if (delegating) {
            gestureDelegate?.invoke(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                delegating = false
            }
            return true
        }
        if (!isEnabled) return false

        val toolType = event.getToolType(0)
        val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
        if (stylusOnly && !isStylus) {
            // 防误触：手指和手掌交给画布做平移、缩放、翻页
            return false
        }

        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (x < 0 || x > view.width || y < 0 || y > view.height) return false
                // 手指可以书写时，接触面积像手掌的触摸不画线，交给画布忽略掉
                if (!isStylus && event.getTouchMajor(0) > PALM_DP * view.resources.displayMetrics.density) return false
                if (!isStylus && gestureDelegate != null && interceptFinger(event)) {
                    delegating = true
                    gestureDelegate?.invoke(event)
                    return true
                }
                currentPoints.clear()
                fingerStroke = !isStylus
                val buttonDown = event.buttonState and
                    (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY) != 0
                kind = when {
                    toolType == MotionEvent.TOOL_TYPE_ERASER -> Kind.ERASE
                    buttonDown && buttonAction == StylusButtonAction.ERASER -> Kind.ERASE
                    buttonDown && buttonAction != StylusButtonAction.NONE -> Kind.ALT
                    else -> Kind.DRAW
                }
                if (isStylus) listener.onPenDown()
                if (isStylus && Build.VERSION.SDK_INT >= 30) view.requestUnbufferedDispatch(event)
                recordPrediction(event)
                addPoint(event, x, y)
                emitMove(listener, withPrediction = false)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (fingerStroke && gestureDelegate != null) {
                    cancelStroke(listener)
                    delegating = true
                    gestureDelegate?.invoke(event)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (currentPoints.isEmpty()) return false
                // 收集高精度历史点（针对 120Hz/144Hz 高刷平板如小米平板、vivo Pad 的高频采样）
                for (h in 0 until event.historySize) {
                    currentPoints.add(
                        InkPoint(
                            x = event.getHistoricalX(h),
                            y = event.getHistoricalY(h),
                            pressure = normalize(event.getHistoricalPressure(h)),
                            t = event.getHistoricalEventTime(h),
                            tilt = event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, h),
                        )
                    )
                }
                addPoint(event, x, y)
                recordPrediction(event)
                emitMove(listener, withPrediction = true)
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (currentPoints.isEmpty()) return false
                addPoint(event, x, y)
                val points = currentPoints.toList()
                currentPoints.clear()
                when (kind) {
                    Kind.ERASE -> listener.onErase(points)
                    Kind.ALT -> listener.onAltStroke(points)
                    Kind.DRAW -> listener.onStroke(points)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelStroke(listener)
                return true
            }
        }
        return false
    }

    private fun cancelStroke(listener: PenInputListener) {
        currentPoints.clear()
        when (kind) {
            Kind.ERASE -> listener.onErasing(emptyList())
            Kind.ALT -> listener.onAltDrawing(emptyList())
            Kind.DRAW -> listener.onDrawing(emptyList())
        }
    }

    private fun emitMove(listener: PenInputListener, withPrediction: Boolean) {
        val points = currentPoints.toList()
        when (kind) {
            Kind.ERASE -> listener.onErasing(points)
            Kind.ALT -> listener.onAltDrawing(points)
            Kind.DRAW -> listener.onDrawing(if (withPrediction) points + predictedPoints() else points)
        }
    }

    private fun recordPrediction(event: MotionEvent) {
        if (Build.VERSION.SDK_INT < 34 || !predictionEnabled) return
        runCatching { (predictor as? MotionPredictor)?.record(event) }
    }

    /** 系统预测的后续几个点，只用于预览，不写入最终笔画。 */
    private fun predictedPoints(): List<InkPoint> {
        if (Build.VERSION.SDK_INT < 34 || !predictionEnabled || fingerStroke) return emptyList()
        val p = predictor as? MotionPredictor ?: return emptyList()
        val predicted = runCatching { p.predict(System.nanoTime()) }.getOrNull() ?: return emptyList()
        val last = currentPoints.lastOrNull() ?: return emptyList()
        val out = ArrayList<InkPoint>()
        for (h in 0 until predicted.historySize) {
            out += InkPoint(predicted.getHistoricalX(h), predicted.getHistoricalY(h), last.pressure, last.t, last.tilt)
        }
        out += InkPoint(predicted.x, predicted.y, last.pressure, last.t, last.tilt)
        predicted.recycle()
        return out
    }

    private fun addPoint(event: MotionEvent, x: Float, y: Float) {
        currentPoints.add(
            InkPoint(
                x = x,
                y = y,
                pressure = normalize(event.pressure),
                t = event.eventTime,
                tilt = event.getAxisValue(MotionEvent.AXIS_TILT),
            )
        )
    }

    private fun normalize(p: Float): Float = p.coerceIn(0f, 1f).let { if (it == 0f) 0.5f else it }

    override fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
    }

    override fun setStrokeWidth(width: Float) = Unit

    override fun setGestureDelegate(delegate: ((MotionEvent) -> Boolean)?, interceptFinger: (MotionEvent) -> Boolean) {
        gestureDelegate = delegate
        this.interceptFinger = interceptFinger
    }

    override fun setStylusButtonAction(action: StylusButtonAction) {
        buttonAction = action
    }

    override fun setPredictionEnabled(enabled: Boolean) {
        predictionEnabled = enabled
    }

    override fun detach() {
        view?.setOnTouchListener(null)
        view?.setOnHoverListener(null)
        view = null
        listener = null
        predictor = null
        currentPoints.clear()
    }

    companion object {
        /** 接触长轴超过这个尺寸（dp）视为手掌。 */
        private const val PALM_DP = 28f
    }
}
