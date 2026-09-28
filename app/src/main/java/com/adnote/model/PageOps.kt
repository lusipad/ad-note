package com.adnote.model

/**
 * 笔记页面结构操作：插页、复制、删除、移动。
 * 全部返回新的 [Note]（不可变），非法下标时原样返回。PDF 笔记的页面与原文一一对应，不允许改动结构。
 */
object PageOps {

    fun canEditStructure(note: Note): Boolean = !note.isPdf

    /** 在 [index] 之后插入一张新空白页，沿用该页的尺寸、底纹和纸色。 */
    fun insertBlankAfter(note: Note, index: Int, now: Long = System.currentTimeMillis()): Note {
        if (!canEditStructure(note) || index !in note.pages.indices) return note
        val ref = note.pages[index]
        val page = Page(
            width = ref.width,
            height = ref.height,
            template = ref.template,
            backgroundColor = ref.backgroundColor,
        )
        return note.copy(pages = note.pages.toMutableList().apply { add(index + 1, page) }, updatedAt = now)
    }

    /** 复制第 [index] 页（含笔迹）并插在其后。复制出的页面和笔画都会分配新 id。 */
    fun duplicate(note: Note, index: Int, now: Long = System.currentTimeMillis()): Note {
        if (!canEditStructure(note) || index !in note.pages.indices) return note
        val src = note.pages[index]
        val copy = src.copy(id = newId(), strokes = src.strokes.map { it.copy(id = newId()) })
        return note.copy(pages = note.pages.toMutableList().apply { add(index + 1, copy) }, updatedAt = now)
    }

    /** 删除第 [index] 页。笔记至少保留一页。 */
    fun delete(note: Note, index: Int, now: Long = System.currentTimeMillis()): Note {
        if (!canEditStructure(note) || index !in note.pages.indices || note.pages.size <= 1) return note
        return note.copy(pages = note.pages.toMutableList().apply { removeAt(index) }, updatedAt = now)
    }

    /** 把第 [from] 页移动到位置 [to]。 */
    fun move(note: Note, from: Int, to: Int, now: Long = System.currentTimeMillis()): Note {
        if (!canEditStructure(note) || from !in note.pages.indices || to !in note.pages.indices || from == to) {
            return note
        }
        val list = note.pages.toMutableList()
        val page = list.removeAt(from)
        list.add(to, page)
        return note.copy(pages = list, updatedAt = now)
    }

    /** 清空第 [index] 页的全部笔迹。 */
    fun clearStrokes(note: Note, index: Int, now: Long = System.currentTimeMillis()): Note {
        if (index !in note.pages.indices || note.pages[index].strokes.isEmpty()) return note
        val list = note.pages.toMutableList()
        list[index] = list[index].copy(strokes = emptyList())
        return note.copy(pages = list, updatedAt = now)
    }
}
