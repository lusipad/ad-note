package com.adnote.pen

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode

object EinkRefresher {

    private var pageTurns = 0

    /**
     * 全刷屏幕以消除残影。
     * 1. 优先调用文石 SDK EpdController (若在文石/得到设备上)。
     * 2. 在掌阅 (iReader)、汉王等非文石墨水屏上，执行通用黑白交替闪刷，利用微胶囊物理特性消除残影。
     * 3. 在普通彩屏（小米、vivo 等平板）上，仅执行常规重绘，不发生任何黑白闪烁。
     */
    fun fullRefresh(view: View) {
        pageTurns = 0
        val onyxSuccess = runCatching {
            EpdController.refreshScreen(view, UpdateMode.GC)
            true
        }.recoverCatching {
            EpdController.repaintEveryThing(UpdateMode.GC)
            true
        }.getOrDefault(false)

        if (onyxSuccess) return

        val deviceInfo = DeviceDetector.detect()
        if (deviceInfo.screenCategory == ScreenCategory.EINK) {
            flashUniversalEink(view)
        } else {
            view.invalidate()
        }
    }

    /**
     * 翻页刷新：平时用文石的快速局部刷新（GU/REGAL，无黑闪），每翻 [fullEvery] 页做一次全刷清残影。
     * [fullEvery] 为 0 表示从不自动全刷。普通彩屏上什么也不做（正常重绘即可）。
     */
    fun onPageTurned(view: View, fullEvery: Int) {
        pageTurns++
        if (fullEvery > 0 && pageTurns >= fullEvery) {
            if (DeviceDetector.detect().screenCategory == ScreenCategory.EINK) fullRefresh(view) else pageTurns = 0
            return
        }
        val mode = modeNamed("REGAL") ?: modeNamed("GU") ?: return
        runCatching { EpdController.refreshScreen(view, mode) }
    }

    /** 按名称取刷新模式；不同 SDK 版本提供的模式不同，找不到返回 null。 */
    private fun modeNamed(name: String): UpdateMode? =
        runCatching { UpdateMode::class.java.enumConstants?.firstOrNull { it.toString() == name } }.getOrNull()

    /**
     * 通用墨水屏物理闪刷：在掌阅、汉王等无公开 API 的墨水屏上，通过瞬时全屏黑白反转
     * 触发电子纸硬件控制器刷新微胶囊，彻底消除残影。
     */
    private fun flashUniversalEink(view: View) {
        val root = view.rootView as? ViewGroup ?: run {
            view.invalidate()
            return
        }

        val overlay = View(view.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        root.addView(overlay)

        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { root.removeView(overlay) }
            view.invalidate()
        }, 60)
    }
}
