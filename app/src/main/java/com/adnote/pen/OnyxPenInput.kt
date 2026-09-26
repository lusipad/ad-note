package com.adnote.pen

import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import com.adnote.model.InkPoint
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList

class OnyxPenInput : PenInput {

    override val name: String = "文石低延迟通道 (TouchHelper)"

    private var touchHelper: TouchHelper? = null
    private var listener: PenInputListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val currentStrokePoints = ArrayList<InkPoint>()
    private val currentErasePoints = ArrayList<InkPoint>()

    override fun attach(view: View, limitRect: Rect, listener: PenInputListener) {
        this.listener = listener

        val callback = object : RawInputCallback() {
            override fun onBeginRawDrawing(b: Boolean, touchPoint: TouchPoint) {
                synchronized(currentStrokePoints) {
                    currentStrokePoints.clear()
                    currentStrokePoints.add(touchPoint.toInkPoint())
                }
            }

            override fun onEndRawDrawing(b: Boolean, touchPoint: TouchPoint) {
                val points = synchronized(currentStrokePoints) {
                    currentStrokePoints.add(touchPoint.toInkPoint())
                    currentStrokePoints.toList()
                }
                mainHandler.post {
                    this@OnyxPenInput.listener?.onStroke(points)
                }
            }

            override fun onRawDrawingTouchPointMoveReceived(touchPoint: TouchPoint) {
                synchronized(currentStrokePoints) {
                    currentStrokePoints.add(touchPoint.toInkPoint())
                }
            }

            override fun onRawDrawingTouchPointListReceived(touchPointList: TouchPointList) {
                synchronized(currentStrokePoints) {
                    for (pt in touchPointList.points) {
                        currentStrokePoints.add(pt.toInkPoint())
                    }
                }
            }

            override fun onBeginRawErasing(b: Boolean, touchPoint: TouchPoint) {
                synchronized(currentErasePoints) {
                    currentErasePoints.clear()
                    currentErasePoints.add(touchPoint.toInkPoint())
                }
            }

            override fun onEndRawErasing(b: Boolean, touchPoint: TouchPoint) {
                val points = synchronized(currentErasePoints) {
                    currentErasePoints.add(touchPoint.toInkPoint())
                    currentErasePoints.toList()
                }
                mainHandler.post {
                    this@OnyxPenInput.listener?.onErase(points)
                }
            }

            override fun onRawErasingTouchPointMoveReceived(touchPoint: TouchPoint) {
                synchronized(currentErasePoints) {
                    currentErasePoints.add(touchPoint.toInkPoint())
                }
            }

            override fun onRawErasingTouchPointListReceived(touchPointList: TouchPointList) {
                synchronized(currentErasePoints) {
                    for (pt in touchPointList.points) {
                        currentErasePoints.add(pt.toInkPoint())
                    }
                }
            }
        }

        try {
            touchHelper = TouchHelper.create(view, callback).apply {
                setStrokeStyle(TouchHelper.STROKE_STYLE_PENCIL)
                setStrokeColor(Color.BLACK)
                setLimitRect(listOf(limitRect))
                openRawDrawing()
                enableSideBtnErase(true)
            }
            Log.i("OnyxPenInput", "TouchHelper 成功初始化并在 limitRect=$limitRect 上启动")
        } catch (t: Throwable) {
            Log.e("OnyxPenInput", "TouchHelper 初始化失败", t)
            throw t
        }
    }

    override fun setEnabled(enabled: Boolean) {
        runCatching {
            touchHelper?.setRawDrawingEnabled(enabled)
        }
    }

    override fun setStrokeWidth(width: Float) {
        runCatching {
            touchHelper?.setStrokeWidth(width)
        }
    }

    override fun detach() {
        runCatching {
            touchHelper?.closeRawDrawing()
        }
        touchHelper = null
        listener = null
    }

    private fun TouchPoint.toInkPoint(): InkPoint =
        InkPoint(
            x = this.x,
            y = this.y,
            pressure = (this.pressure / 4096f).coerceIn(0f, 1f).let { if (it == 0f) 0.5f else it },
            t = if (this.timestamp > 0) this.timestamp else System.currentTimeMillis()
        )
}
