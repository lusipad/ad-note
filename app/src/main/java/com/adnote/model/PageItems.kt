package com.adnote.model

import kotlinx.serialization.Serializable

/** 图层。id 在同一页内唯一。 */
@Serializable
data class Layer(
    val id: Int,
    val name: String,
    val visible: Boolean = true,
) {
    companion object {
        val DEFAULT = Layer(0, "图层 1")
    }
}

/**
 * 文字框。(x, y) 为左上角的页面坐标；超出 [maxWidth] 或页面右边距时自动换行。
 * [linkPageId] 非空时是一个页面链接，手指点按即跳转。
 */
@Serializable
data class TextBox(
    val id: String = newId(),
    val x: Float,
    val y: Float,
    val text: String,
    val size: Float = 36f,
    val color: String = "#000000",
    val layer: Int = 0,
    val maxWidth: Float = 0f,
    val linkPageId: String? = null,
)

/** 插入的图片。[path] 相对笔记目录（如 "images/ab12.jpg"）。 */
@Serializable
data class ImageItem(
    val id: String = newId(),
    val path: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val layer: Int = 0,
)

/** 一段录音。[path] 相对笔记目录；[pageId] 为开始录音时所在页。 */
@Serializable
data class Recording(
    val id: String = newId(),
    val path: String,
    val createdAt: Long,
    val durationMs: Long,
    val pageId: String? = null,
)

/** 某个图层上的全部内容，按绘制顺序：图片 → 笔画 → 文字。 */
data class LayerContent(
    val layer: Layer,
    val strokes: List<Stroke>,
    val texts: List<TextBox>,
    val images: List<ImageItem>,
)

/**
 * 按图层顺序分组页面内容。引用了不存在图层的内容归入第一个图层，保证不会凭空消失。
 * [includeHidden] 为 false 时跳过隐藏图层。
 */
fun Page.layerContents(includeHidden: Boolean = false): List<LayerContent> {
    val layerList = layers.ifEmpty { listOf(Layer.DEFAULT) }
    val known = layerList.map { it.id }.toSet()
    val fallback = layerList.first().id
    fun owner(id: Int) = if (id in known) id else fallback
    return layerList.filter { includeHidden || it.visible }.map { l ->
        LayerContent(
            layer = l,
            strokes = strokes.filter { owner(it.layer) == l.id },
            texts = texts.filter { owner(it.layer) == l.id },
            images = images.filter { owner(it.layer) == l.id },
        )
    }
}
