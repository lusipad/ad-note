package com.adnote.model

import kotlinx.serialization.Serializable

/**
 * 笔型。决定压感到笔宽的映射、透明度与端点形态。
 *
 * 旧版本笔迹没有 pen 字段，反序列化时默认为 [FOUNTAIN]，渲染结果与旧版完全一致。
 */
@Serializable
enum class PenType(
    val displayName: String,
    /** 压感为 0 时的宽度倍率。 */
    private val minFactor: Float,
    /** 压感为 1 时的宽度倍率。 */
    private val maxFactor: Float,
    /** 不透明度 0..1。 */
    val opacity: Float,
    /** 等宽笔：用描边折线渲染（圆头圆角），而不是压感轮廓多边形。 */
    val constantWidth: Boolean = false,
    /** 起笔与收笔是否做尖锋收细。 */
    val taper: Boolean = false,
) {
    FOUNTAIN("钢笔", 0.4f, 1.2f, 1f),
    BALLPOINT("圆珠笔", 0.8f, 1.05f, 1f),
    PENCIL("铅笔", 0.45f, 1.0f, 0.72f),
    BRUSH("毛笔", 0.15f, 1.9f, 1f, taper = true),
    MARKER("马克笔", 1f, 1f, 1f, constantWidth = true),
    HIGHLIGHTER("荧光笔", 1f, 1f, 0.35f, constantWidth = true);

    /** 压感映射到宽度倍率。 */
    fun widthFactor(pressure: Float): Float =
        minFactor + (maxFactor - minFactor) * pressure.coerceIn(0f, 1f)

    companion object {
        /** 普通「笔」工具可选的笔型（荧光笔是独立工具）。 */
        val WRITING = listOf(FOUNTAIN, BALLPOINT, PENCIL, BRUSH, MARKER)
    }
}
