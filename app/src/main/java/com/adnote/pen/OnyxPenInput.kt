package com.adnote.pen

import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import com.adnote.model.InkPoint
import com.adnote.model.PenType
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList

class OnyxPenInput : PenInput {

    override val name: String = "文石低延迟通道 (TouchHelper)"

    private var touchHelper: TouchHelper? = null
    private var listener: PenInputListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 设备压感最大值：不同型号不同（4096 / 8192），优先向固件查询。 */
    private val maxPressure: Float by lazy {
        runCatching {
            val epd = Class.forName("com.onyx.android.sdk.api.device.epd.EpdController")
            (epd.getMethod("getMaxTouchPressure").invoke(null) as Number).toFloat()
        }.getOrNull()?.takeIf { it > 0f } ?: 4096f
    }

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
                mainHandler.post { this@OnyxPenInput.listener?.onPenDown() }
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
                mainHandler.post { this@OnyxPenInput.listener?.onPenDown() }
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

    override fun setStrokeColor(color: Int) {
        runCatching {
            touchHelper?.setStrokeColor(color)
        }
    }

    override fun setPenStyle(pen: PenType) {
        val helper = touchHelper ?: return
        // 不同版本 SDK 提供的笔锋常量不完全相同，用反射按名称查找，找不到就退回铅笔笔锋
        val names = when (pen) {
            PenType.FOUNTAIN -> listOf("STROKE_STYLE_FOUNTAIN")
            PenType.BRUSH -> listOf("STROKE_STYLE_NEO_BRUSH", "STROKE_STYLE_BRUSH", "STROKE_STYLE_FOUNTAIN")
            PenType.MARKER, PenType.HIGHLIGHTER -> listOf("STROKE_STYLE_MARKER")
            PenType.PENCIL -> listOf("STROKE_STYLE_CHARCOAL", "STROKE_STYLE_PENCIL")
            PenType.BALLPOINT -> listOf("STROKE_STYLE_PENCIL")
        }
        val style = names.firstNotNullOfOrNull { name ->
            runCatching { TouchHelper::class.java.getField(name).getInt(null) }.getOrNull()
        } ?: TouchHelper.STROKE_STYLE_PENCIL
        runCatching { helper.setStrokeStyle(style) }
    }

    override fun setRenderEnabled(enabled: Boolean) {
        val helper = touchHelper ?: return
        // setRawDrawingRenderEnabled 仅在较新的 SDK 中存在
        runCatching {
            helper.javaClass.getMethod("setRawDrawingRenderEnabled", Boolean::class.javaPrimitiveType)
                .invoke(helper, enabled)
        }.onFailure { Log.w("OnyxPenInput", "当前 SDK 不支持关闭直绘渲染: ${it.message}") }
    }

    override fun setLimitRect(rect: Rect) {
        runCatching { touchHelper?.setLimitRect(listOf(rect)) }
            .onFailure { Log.w("OnyxPenInput", "更新书写区域失败: ${it.message}") }
    }

    override fun setExcludeRects(rects: List<Rect>) {
        val helper = touchHelper ?: return
        runCatching {
            helper.javaClass.getMethod("setExcludeRect", List::class.java).invoke(helper, rects)
        }.onFailure { Log.w("OnyxPenInput", "当前 SDK 不支持排除区域: ${it.message}") }
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
            pressure = (this.pressure / maxPressure).coerceIn(0f, 1f).let { if (it == 0f) 0.5f else it },
            t = if (this.timestamp > 0) this.timestamp else System.currentTimeMillis()
        )
}
