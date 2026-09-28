package com.adnote.pen

import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import com.adnote.model.InkPoint
import com.adnote.model.PenType
import com.adnote.model.StylusButtonAction

/** 画笔通道回调。坐标均为画布视图坐标，由调用方换算为页面坐标。 */
interface PenInputListener {
    /** 笔画书写完成（提笔） */
    fun onStroke(points: List<InkPoint>)

    /** 橡皮擦除轨迹完成 */
    fun onErase(points: List<InkPoint>)

    /** 笔迹移动中实时预览（普通彩屏及高刷平板使用）；空列表表示本笔被取消 */
    fun onDrawing(points: List<InkPoint>) {}

    /** 橡皮（笔尾/侧键）移动中的实时轨迹，用于边擦边显示 */
    fun onErasing(points: List<InkPoint>) {}

    /** 按住笔身按键书写（按键被设置为套索、荧光笔时） */
    fun onAltDrawing(points: List<InkPoint>) {}

    fun onAltStroke(points: List<InkPoint>) {}

    /** 笔尖（或笔尾橡皮）接触屏幕，一次书写开始。用于屏蔽同时搭在屏幕上的手掌。 */
    fun onPenDown() {}

    /** 笔尖悬停在屏幕上方 */
    fun onHover(x: Float, y: Float, eraser: Boolean) {}

    fun onHoverExit() {}
}

interface PenInput {
    val name: String

    fun attach(view: View, limitRect: Rect, listener: PenInputListener)

    fun setEnabled(enabled: Boolean)

    fun setStrokeWidth(width: Float)

    fun setStrokeColor(color: Int) {}

    /** 切换硬件直绘的笔型（文石固件支持钢笔、马克笔、毛笔等笔锋）。 */
    fun setPenStyle(pen: PenType) {}

    /**
     * 是否由硬件直绘层实时画出笔迹。橡皮、套索、荧光笔等模式下关闭，
     * 由应用自己绘制光标或在抬笔后呈现结果。
     */
    fun setRenderEnabled(enabled: Boolean) {}

    /** 画布上浮动面板所在的屏幕区域，直绘层不应拦截这些区域的笔触。 */
    fun setExcludeRects(rects: List<Rect>) {}

    /**
     * 手指同时按下多指时，把事件转交给画布处理缩放和多指点按。
     * [interceptFinger] 对单指按下返回 true 时（如按在直尺上），这一整次触摸也转交给画布。
     */
    fun setGestureDelegate(delegate: ((MotionEvent) -> Boolean)?, interceptFinger: (MotionEvent) -> Boolean = { false }) {}

    fun setStylusButtonAction(action: StylusButtonAction) {}

    /** 是否在预览笔迹末端追加系统预测的点（降低视觉延迟），只在书写工具下开启。 */
    fun setPredictionEnabled(enabled: Boolean) {}

    fun detach()
}
