package com.adnote.ink

import com.adnote.model.InkPoint

/**
 * 收集硬件直绘通道（文石 TouchHelper）一笔的采样点。
 *
 * 文石 SDK 的回调顺序是：起笔 → 逐点移动 → 整笔点列表 → 抬笔。整笔列表已经包含全部采样点，
 * 如果把逐点移动和整笔列表都收进来，每个点都会出现两次：笔画从终点直线折回起点再重画一遍，
 * 套索多边形绕两圈导致什么都圈不中，橡皮沿着这条折线误擦。
 *
 * 这里以整笔列表为准；固件没有给列表时退回逐点移动收集到的点。
 * 列表分批到达时：新的一批与已有列表起点相同视为累计（整体替换），否则视为增量（接在后面）。
 */
class RawStrokeCollector {

    private val movePoints = ArrayList<InkPoint>()
    private var listPoints: MutableList<InkPoint>? = null
    private var active = false

    @Synchronized
    fun begin(p: InkPoint) {
        movePoints.clear()
        movePoints += p
        listPoints = null
        active = true
    }

    @Synchronized
    fun move(p: InkPoint) {
        if (active) movePoints += p
    }

    @Synchronized
    fun list(points: List<InkPoint>) {
        if (!active || points.isEmpty()) return
        val existing = listPoints
        listPoints = if (existing == null || existing.isEmpty() || samePoint(existing[0], points[0])) {
            points.toMutableList()
        } else {
            existing.apply { addAll(points) }
        }
    }

    /** 抬笔，返回这一笔的全部点；没有进行中的笔画时返回空列表。 */
    @Synchronized
    fun end(p: InkPoint): List<InkPoint> {
        if (!active) return emptyList()
        active = false
        val fromList = listPoints
        val out = ArrayList<InkPoint>()
        if (fromList != null && fromList.size >= 2) {
            // 起笔点通常已在列表里；不在时补在最前面
            if (!samePoint(fromList[0], movePoints[0])) out += movePoints[0]
            out += fromList
        } else {
            out += movePoints
        }
        if (!samePoint(out.last(), p)) out += p
        movePoints.clear()
        listPoints = null
        return out
    }

    /** 只比较坐标：部分固件不给时间戳，两次换算出的时间会不同。 */
    private fun samePoint(a: InkPoint, b: InkPoint): Boolean = a.x == b.x && a.y == b.y
}
