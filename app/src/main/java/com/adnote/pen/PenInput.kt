package com.adnote.pen

import android.graphics.Rect
import android.view.View
import com.adnote.model.InkPoint

interface PenInputListener {
    /** 笔画书写完成（提笔） */
    fun onStroke(points: List<InkPoint>)

    /** 橡皮擦除轨迹完成 */
    fun onErase(points: List<InkPoint>)

    /** 笔迹移动中实时预览（普通彩屏及高刷平板使用） */
    fun onDrawing(points: List<InkPoint>) {}
}

interface PenInput {
    val name: String

    fun attach(view: View, limitRect: Rect, listener: PenInputListener)

    fun setEnabled(enabled: Boolean)

    fun setStrokeWidth(width: Float)

    fun detach()
}
