package com.adnote.model

import kotlin.math.max
import kotlin.math.min

/**
 * 画布视口：页面按宽度铺满视图（[baseScale]），在此基础上缩放 [zoom] 倍并平移。
 * 视图坐标 = 页面坐标 × scale − pan。
 */
data class Viewport(
    val viewW: Int,
    val viewH: Int,
    val pageW: Int,
    val pageH: Int,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    val baseScale: Float get() = if (pageW > 0) viewW.toFloat() / pageW else 1f
    val scale: Float get() = baseScale * zoom

    fun toPageX(vx: Float) = (vx + panX) / scale
    fun toPageY(vy: Float) = (vy + panY) / scale
    fun toViewX(px: Float) = px * scale - panX
    fun toViewY(py: Float) = py * scale - panY

    /** 限制平移范围：内容比视图小时水平居中、顶部对齐。 */
    fun clamped(): Viewport {
        val cw = pageW * scale; val ch = pageH * scale
        val px = if (cw <= viewW) (cw - viewW) / 2f else panX.coerceIn(0f, cw - viewW)
        val py = if (ch <= viewH) 0f else panY.coerceIn(0f, ch - viewH)
        return if (px == panX && py == panY) this else copy(panX = px, panY = py)
    }

    /** 以视图坐标 (fx, fy) 为焦点缩放，焦点下的页面内容保持不动。 */
    fun zoomBy(factor: Float, fx: Float, fy: Float): Viewport {
        val nz = min(MAX_ZOOM, max(MIN_ZOOM, zoom * factor))
        val pxPage = toPageX(fx); val pyPage = toPageY(fy)
        val ns = baseScale * nz
        return copy(zoom = nz, panX = pxPage * ns - fx, panY = pyPage * ns - fy).clamped()
    }

    fun panBy(dx: Float, dy: Float): Viewport = copy(panX = panX - dx, panY = panY - dy).clamped()

    fun reset(): Viewport = copy(zoom = 1f, panX = 0f, panY = 0f).clamped()

    val isZoomed: Boolean get() = zoom > 1.01f

    /** 页面是否比视图高（需要上下滚动）。 */
    val scrollsVertically: Boolean get() = pageH * scale > viewH + 1f

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 5f
    }
}
