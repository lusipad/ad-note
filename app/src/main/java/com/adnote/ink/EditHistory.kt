package com.adnote.ink

/**
 * 撤销/重做历史，保存的是整份内容的快照。
 *
 * 编辑器用它保存整页 [com.adnote.model.Page] 快照：页面数据不可变、内部对象共享，
 * 快照几乎不占额外内存，因此书写、擦除、文字、图片、图层、选区变换都能用同一套机制撤销。
 */
class EditHistory<T>(private val limit: Int = 100) {

    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** 在修改发生后调用，传入修改前的状态。 */
    fun record(before: T) {
        undoStack.addLast(before)
        while (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }

    /** 返回应恢复的状态；没有可撤销的操作时返回 null。 */
    fun undo(current: T): T? {
        val prev = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        return prev
    }

    fun redo(current: T): T? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        return next
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
