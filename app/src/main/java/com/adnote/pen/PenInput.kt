package com.adnote.pen

import android.graphics.Rect
import android.view.View
import com.adnote.model.InkPoint
import com.adnote.model.PenType

interface PenInputListener {
    /** 笔画书写完成（提笔） */
    fun onStroke(points: List<InkPoint>)

    /** 橡皮擦除轨迹完成 */
    fun onErase(points: List<InkPoint>)

    /** 笔迹移动中实时预览（普通彩屏及高刷平板使用） */
    fun onDrawing(points: List<InkPoint>) {}

    /** 橡皮（笔尾/侧键）移动中的实时轨迹，用于边擦边显示 */
    fun onErasing(points: List<InkPoint>) {}
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

    fun detach()
}
