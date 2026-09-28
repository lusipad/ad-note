package com.adnote.ink

import com.adnote.model.InkPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawStrokeCollectorTest {

    private fun pts(vararg xs: Int) = xs.map { InkPoint(it.toFloat(), it * 2f, t = it.toLong()) }

    @Test
    fun moveAndListCallbacksDoNotDuplicatePoints() {
        // 文石回调顺序：起笔 → 逐点移动 → 整笔列表 → 抬笔
        val c = RawStrokeCollector()
        val all = pts(0, 1, 2, 3, 4)
        c.begin(all[0])
        all.drop(1).dropLast(1).forEach { c.move(it) }
        c.list(all.dropLast(1))
        val out = c.end(all.last())
        assertEquals(all.map { it.x }, out.map { it.x })
    }

    @Test
    fun lassoFromDeviceCallbacksSelectsEnclosedStroke() {
        // 之前每个点收两次，套索多边形绕两圈，射线法判定永远在外面
        val square = listOf(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f, 0f to 1f)
            .map { (x, y) -> InkPoint(x, y) }
        val c = RawStrokeCollector()
        c.begin(square[0])
        square.drop(1).dropLast(1).forEach { c.move(it) }
        c.list(square.dropLast(1))
        val lasso = c.end(square.last())
        assertTrue(Lasso.contains(lasso, 50f, 50f))
    }

    @Test
    fun fallsBackToMovePointsWithoutList() {
        val c = RawStrokeCollector()
        val all = pts(0, 1, 2, 3)
        c.begin(all[0])
        c.move(all[1]); c.move(all[2])
        assertEquals(all.map { it.x }, c.end(all[3]).map { it.x })
    }

    @Test
    fun cumulativeListsReplaceIncrementalListsAppend() {
        val all = pts(0, 1, 2, 3, 4, 5)
        val cumulative = RawStrokeCollector().apply {
            begin(all[0]); list(all.subList(0, 3)); list(all.subList(0, 5))
        }.end(all[5])
        assertEquals(all.map { it.x }, cumulative.map { it.x })

        val incremental = RawStrokeCollector().apply {
            begin(all[0]); list(all.subList(0, 3)); list(all.subList(3, 5))
        }.end(all[5])
        assertEquals(all.map { it.x }, incremental.map { it.x })
    }

    @Test
    fun endWithoutBeginReturnsNothing() {
        assertEquals(emptyList<InkPoint>(), RawStrokeCollector().end(InkPoint(1f, 1f)))
    }
}
