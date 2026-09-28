package com.adnote.ink

import com.adnote.model.Stroke

/**
 * 单页笔迹的撤销/重做历史。
 *
 * 保存的是整页笔画列表的快照。笔画列表不可变且笔画对象共享，快照几乎不占额外内存，
 * 因此书写、擦除、清空、移动选区都能用同一套机制撤销。
 */
class StrokeHistory(private val limit: Int = 100) {

    private val undoStack = ArrayDeque<List<Stroke>>()
    private val redoStack = ArrayDeque<List<Stroke>>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** 在修改发生后调用，传入修改前的笔画列表。 */
    fun record(before: List<Stroke>) {
        undoStack.addLast(before)
        while (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }

    /** 返回应恢复的笔画列表；没有可撤销的操作时返回 null。 */
    fun undo(current: List<Stroke>): List<Stroke>? {
        val prev = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        return prev
    }

    fun redo(current: List<Stroke>): List<Stroke>? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        return next
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
