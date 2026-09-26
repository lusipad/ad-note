package com.adnote.pen

import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import com.adnote.model.InkPoint

class MotionPenInput(
    var allowFingerOrMouse: Boolean = true,
) : PenInput {

    override val name: String = "标准触控通道 (MotionEvent)"

    private var view: View? = null
    private var limitRect: Rect? = null
    private var listener: PenInputListener? = null
    private var isEnabled: Boolean = true
    private var strokeWidth: Float = 3f

    private val currentPoints = ArrayList<InkPoint>()
    private var isCurrentEraser = false

    override fun attach(view: View, limitRect: Rect, listener: PenInputListener) {
        this.view = view
        this.limitRect = limitRect
        this.listener = listener

        view.setOnTouchListener { _, event ->
            if (!isEnabled) return@setOnTouchListener false

            val toolType = event.getToolType(0)
            val isStylusOrEraser = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
            if (!isStylusOrEraser && !allowFingerOrMouse) {
                return@setOnTouchListener false // 防手掌误触
            }

            val x = event.x
            val y = event.y
            val inLimit = limitRect.contains(x.toInt(), y.toInt())

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!inLimit) return@setOnTouchListener false
                    currentPoints.clear()
                    isCurrentEraser = toolType == MotionEvent.TOOL_TYPE_ERASER ||
                        (event.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY) != 0)
                    addPoint(event, x, y)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (currentPoints.isEmpty()) return@setOnTouchListener false
                    // 收集高精度历史点
                    for (h in 0 until event.historySize) {
                        currentPoints.add(
                            InkPoint(
                                x = event.getHistoricalX(h),
                                y = event.getHistoricalY(h),
                                pressure = event.getHistoricalPressure(h).coerceIn(0f, 1f).let { if (it == 0f) 0.5f else it },
                                t = event.getHistoricalEventTime(h)
                            )
                        )
                    }
                    addPoint(event, x, y)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (currentPoints.isEmpty()) return@setOnTouchListener false
                    addPoint(event, x, y)
                    val points = currentPoints.toList()
                    currentPoints.clear()

                    if (isCurrentEraser) {
                        listener.onErase(points)
                    } else {
                        listener.onStroke(points)
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    currentPoints.clear()
                    true
                }

                else -> false
            }
        }
    }

    private fun addPoint(event: MotionEvent, x: Float, y: Float) {
        currentPoints.add(
            InkPoint(
                x = x,
                y = y,
                pressure = event.pressure.coerceIn(0f, 1f).let { if (it == 0f) 0.5f else it },
                t = event.eventTime
            )
        )
    }

    override fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
    }

    override fun setStrokeWidth(width: Float) {
        strokeWidth = width
    }

    override fun detach() {
        view?.setOnTouchListener(null)
        view = null
        listener = null
        currentPoints.clear()
    }
}
