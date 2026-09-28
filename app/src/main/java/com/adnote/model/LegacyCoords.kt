package com.adnote.model

/**
 * 旧版笔迹坐标迁移。
 *
 * v0.2 及以前，画布把页面直接画满视图：笔迹按视图坐标保存，底纹按视图宽度排版，
 * PDF 底图被拉伸到整个视图。现在画布按页面坐标保存并缩放显示，因此旧笔记要换算一次：
 * - 普通页面：底纹间距只取决于宽度，按 页面宽 / 视图宽 等比缩放；
 * - PDF 页面：底图曾被非等比拉伸，横纵分别按 页面宽 / 视图宽、页面高 / 视图高 缩放，
 *   保证批注仍落在原文对应位置。
 */
object LegacyCoords {

    const val CURRENT = 1

    fun needsMigration(note: Note): Boolean = note.coordVersion < CURRENT

    /** [viewW] × [viewH] 为旧版画布尺寸。 */
    fun migrate(note: Note, viewW: Int, viewH: Int): Note {
        if (!needsMigration(note)) return note
        if (viewW <= 0 || viewH <= 0) return note.copy(coordVersion = CURRENT)
        val pages = note.pages.map { page ->
            val sx = page.width.toFloat() / viewW
            val sy = if (note.isPdf) page.height.toFloat() / viewH else sx
            if (page.strokes.isEmpty() || (sx == 1f && sy == 1f)) page
            else page.copy(strokes = page.strokes.map { s ->
                s.copy(
                    points = s.points.map { it.copy(x = it.x * sx, y = it.y * sy) },
                    width = s.width * sx,
                )
            })
        }
        return note.copy(pages = pages, coordVersion = CURRENT)
    }
}
