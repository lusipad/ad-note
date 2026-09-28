package com.adnote.ink

import com.adnote.model.ImageItem
import com.adnote.model.InkPoint
import com.adnote.model.Page
import com.adnote.model.Stroke
import com.adnote.model.TextBox
import com.adnote.model.newId
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** 选区：笔画、文字框、图片的 id。 */
data class Selection(
    val strokes: Set<String> = emptySet(),
    val texts: Set<String> = emptySet(),
    val images: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = strokes.isEmpty() && texts.isEmpty() && images.isEmpty()
    val size: Int get() = strokes.size + texts.size + images.size

    companion object {
        val EMPTY = Selection()
    }
}

/** 剪贴板内容；跨笔记粘贴时需要把图片文件从 [sourceNoteId] 复制过来。 */
data class ClipContent(
    val strokes: List<Stroke>,
    val texts: List<TextBox>,
    val images: List<ImageItem>,
    val sourceNoteId: String,
)

/** 二维仿射变换 x' = a·x + c·y + tx, y' = b·x + d·y + ty。 */
data class Affine(
    val a: Float = 1f, val b: Float = 0f,
    val c: Float = 0f, val d: Float = 1f,
    val tx: Float = 0f, val ty: Float = 0f,
) {
    fun mapX(x: Float, y: Float) = a * x + c * y + tx
    fun mapY(x: Float, y: Float) = b * x + d * y + ty

    /** 线性部分的平均缩放倍数，用于缩放笔宽和字号。 */
    val scale: Float get() = sqrt(kotlin.math.abs(a * d - b * c))

    companion object {
        fun translate(dx: Float, dy: Float) = Affine(tx = dx, ty = dy)

        fun scale(s: Float, px: Float, py: Float) = Affine(a = s, d = s, tx = px - s * px, ty = py - s * py)

        fun rotate(rad: Float, px: Float, py: Float): Affine {
            val cs = cos(rad); val sn = sin(rad)
            return Affine(a = cs, b = sn, c = -sn, d = cs, tx = px - cs * px + sn * py, ty = py - sn * px - cs * py)
        }
    }
}

/** 选区相关的纯函数：套索选择、包围盒、变换、删除、复制粘贴。 */
object SelectionOps {

    /** 套索选择，只在 [layers] 中的图层里选。 */
    fun lasso(page: Page, lasso: List<InkPoint>, layers: Set<Int>): Selection {
        if (lasso.size < 3) return Selection.EMPTY
        val strokes = Lasso.select(page.strokes.filter { it.layer in layers }, lasso)
        val texts = page.texts.filter { it.layer in layers }.filter { t ->
            val b = TextLayout.bounds(t, page.width)
            Lasso.contains(lasso, (b[0] + b[2]) / 2f, (b[1] + b[3]) / 2f)
        }.map { it.id }.toSet()
        val images = page.images.filter { it.layer in layers }.filter { i ->
            Lasso.contains(lasso, i.x + i.width / 2f, i.y + i.height / 2f)
        }.map { it.id }.toSet()
        return Selection(strokes, texts, images)
    }

    /** 选区包围盒 [l, t, r, b]；空选区返回 null。 */
    fun bounds(page: Page, sel: Selection): FloatArray? {
        val boxes = ArrayList<FloatArray>()
        page.strokes.filter { it.id in sel.strokes }.forEach { boxes += StrokeGeometry.bounds(it) }
        page.texts.filter { it.id in sel.texts }.forEach { boxes += TextLayout.bounds(it, page.width) }
        page.images.filter { it.id in sel.images }.forEach { boxes += floatArrayOf(it.x, it.y, it.x + it.width, it.y + it.height) }
        if (boxes.isEmpty()) return null
        return floatArrayOf(
            boxes.minOf { it[0] }, boxes.minOf { it[1] }, boxes.maxOf { it[2] }, boxes.maxOf { it[3] },
        )
    }

    /**
     * 对选区应用变换。笔画逐点变换并按缩放倍数调整笔宽；文字框和图片保持水平，
     * 只变换位置并缩放尺寸（旋转时绕选区中心移动）。
     */
    fun transform(page: Page, sel: Selection, m: Affine): Page {
        if (sel.isEmpty) return page
        val s = m.scale
        return page.copy(
            strokes = page.strokes.map { st ->
                if (st.id !in sel.strokes) st else st.copy(
                    points = st.points.map { p -> p.copy(x = m.mapX(p.x, p.y), y = m.mapY(p.x, p.y)) },
                    width = (st.width * s).coerceIn(0.5f, 200f),
                )
            },
            texts = page.texts.map { t ->
                if (t.id !in sel.texts) t else {
                    val b = TextLayout.bounds(t, page.width)
                    val cx = (b[0] + b[2]) / 2f; val cy = (b[1] + b[3]) / 2f
                    val nx = m.mapX(cx, cy); val ny = m.mapY(cx, cy)
                    val size = (t.size * s).coerceIn(8f, 400f)
                    val k = size / t.size
                    t.copy(
                        x = nx - (cx - t.x) * k, y = ny - (cy - t.y) * k, size = size,
                        maxWidth = if (t.maxWidth > 0f) t.maxWidth * k else 0f,
                    )
                }
            },
            images = page.images.map { i ->
                if (i.id !in sel.images) i else {
                    val cx = i.x + i.width / 2f; val cy = i.y + i.height / 2f
                    val nx = m.mapX(cx, cy); val ny = m.mapY(cx, cy)
                    val w = max(8f, i.width * s); val h = max(8f, i.height * s)
                    i.copy(x = nx - w / 2f, y = ny - h / 2f, width = w, height = h)
                }
            },
        )
    }

    fun delete(page: Page, sel: Selection): Page = page.copy(
        strokes = page.strokes.filterNot { it.id in sel.strokes },
        texts = page.texts.filterNot { it.id in sel.texts },
        images = page.images.filterNot { it.id in sel.images },
    )

    fun recolor(page: Page, sel: Selection, color: String): Page = page.copy(
        strokes = page.strokes.map { if (it.id in sel.strokes) it.copy(color = color) else it },
        texts = page.texts.map { if (it.id in sel.texts) it.copy(color = color) else it },
    )

    fun copy(page: Page, sel: Selection, noteId: String) = ClipContent(
        strokes = page.strokes.filter { it.id in sel.strokes },
        texts = page.texts.filter { it.id in sel.texts },
        images = page.images.filter { it.id in sel.images },
        sourceNoteId = noteId,
    )

    /**
     * 粘贴到 [page] 的 [layer] 图层，整体偏移 (dx, dy)，全部分配新 id。
     * [imagePath] 可改写图片路径（跨笔记复制图片文件后使用）。返回新页面与新选区。
     */
    fun paste(
        page: Page, clip: ClipContent, layer: Int, dx: Float, dy: Float,
        imagePath: (String) -> String = { it },
    ): Pair<Page, Selection> {
        val strokes = clip.strokes.map { s ->
            s.copy(id = newId(), layer = layer, points = s.points.map { it.copy(x = it.x + dx, y = it.y + dy) })
        }
        val texts = clip.texts.map { it.copy(id = newId(), layer = layer, x = it.x + dx, y = it.y + dy) }
        val images = clip.images.map {
            it.copy(id = newId(), layer = layer, x = it.x + dx, y = it.y + dy, path = imagePath(it.path))
        }
        val out = page.copy(strokes = page.strokes + strokes, texts = page.texts + texts, images = page.images + images)
        return out to Selection(strokes.map { it.id }.toSet(), texts.map { it.id }.toSet(), images.map { it.id }.toSet())
    }

    /** 让剪贴板内容的包围盒中心落到 (x, y) 所需的偏移；内容为空时返回 (0, 0)。 */
    fun offsetToCenter(clip: ClipContent, pageWidth: Int, x: Float, y: Float): Pair<Float, Float> {
        val tmp = Page(width = pageWidth, height = Int.MAX_VALUE, strokes = clip.strokes, texts = clip.texts, images = clip.images)
        val sel = Selection(clip.strokes.map { it.id }.toSet(), clip.texts.map { it.id }.toSet(), clip.images.map { it.id }.toSet())
        val b = bounds(tmp, sel) ?: return 0f to 0f
        return (x - (b[0] + b[2]) / 2f) to (y - (b[1] + b[3]) / 2f)
    }

    /** 把选区限制在页面内所需的额外平移。 */
    fun clampInside(b: FloatArray, pageW: Int, pageH: Int): Pair<Float, Float> {
        val dx = when {
            b[0] < 0f -> -b[0]
            b[2] > pageW -> max(pageW - b[2], -b[0])
            else -> 0f
        }
        val dy = when {
            b[1] < 0f -> -b[1]
            b[3] > pageH -> max(pageH - b[3], -b[1])
            else -> 0f
        }
        return dx to dy
    }
}
