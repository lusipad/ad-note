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

    private var fastModeActive = false

    /**
     * 全刷屏幕以消除残影（GC16 波形）。
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
     * 翻页刷新：平时用文石的 16 级灰阶局部无闪刷新（REGAL/GU），每翻 [fullEvery] 页做一次 GC 全刷清残影。
     * [fullEvery] 为 0 表示从不自动全刷。普通彩屏上什么也不做（正常重绘即可）。
     */
    fun onPageTurned(view: View, fullEvery: Int) {
        pageTurns++
        if (fullEvery > 0 && pageTurns >= fullEvery) {
            if (DeviceDetector.detect().screenCategory == ScreenCategory.EINK) fullRefresh(view) else pageTurns = 0
            return
        }
        refreshCanvasPartial(view)
    }

    /**
     * 画布灰阶局部刷新：在内容修改（擦除、撤销、几何修正、荧光笔、弹窗关闭）后刷新画布。
     * 严格优先使用 16 级灰阶模式（REGAL / GU），避免 1-bit DU 二值化模式吞噬浅灰纸张底纹线与铅笔/荧光笔灰阶。
     */
    fun refreshFast(view: View) {
        refreshCanvasPartial(view)
    }

    /**
     * 16 级灰阶无闪局部刷新（REGAL -> GU -> DU 兜底）。
     */
    fun refreshCanvasPartial(view: View) {
        val mode = modeNamed("REGAL") ?: modeNamed("GU") ?: modeNamed("DU") ?: return
        runCatching { EpdController.refreshScreen(view, mode) }
    }

    /**
     * 纯黑白二值 UI 控件极速刷新（优先 DU 模式，~60ms 响应）：仅用于工具栏按钮、页码胶囊等纯黑白控件状态切换。
     */
    fun refreshUiControl(view: View) {
        val mode = modeNamed("DU") ?: modeNamed("GU") ?: return
        runCatching { EpdController.refreshScreen(view, mode) }
    }

    /**
     * 进入连续拖拽/缩放极速交互模式（A2 / Fast Mode）：
     * 用于直尺拖动、套索选区线框变换、双指平移缩放期间，抑制多帧灰阶抖动残影。
     */
    fun enterFastInteractiveMode(view: View) {
        if (fastModeActive) return
        fastModeActive = true
        runCatching {
            val epd = EpdController::class.java
            val m = epd.methods.firstOrNull { it.name == "applyApplicationFastMode" && it.parameterTypes.size == 3 }
            m?.invoke(null, "ad-note", true, true)
        }.onFailure {
            val mode = modeNamed("A2") ?: modeNamed("DU") ?: return@onFailure
            runCatching { EpdController.setViewDefaultUpdateMode(view, mode) }
        }
    }

    /**
     * 退出连续交互极速模式，并立即执行一次 16 级灰阶 REGAL/GU 局部刷新恢复画质。
     */
    fun exitFastInteractiveMode(view: View) {
        if (!fastModeActive) return
        fastModeActive = false
        runCatching {
            val epd = EpdController::class.java
            val m = epd.methods.firstOrNull { it.name == "applyApplicationFastMode" && it.parameterTypes.size == 3 }
            m?.invoke(null, "ad-note", false, true)
        }.onFailure {
            runCatching { EpdController.resetViewUpdateMode(view) }
        }
        refreshCanvasPartial(view)
    }

    /** 按名称取刷新模式；不同 SDK 版本提供的模式不同，找不到返回 null。 */
    private fun modeNamed(name: String): UpdateMode? =
        runCatching { UpdateMode::class.java.enumConstants?.firstOrNull { it.toString() == name } }.getOrNull()

    /**
     * 通用墨水屏物理闪刷：在掌阅、汉王等无公开 API 的墨水屏上，通过全屏黑白反转
     * 触发电子纸硬件控制器刷新微胶囊（保持 140ms 确保跨过墨水屏 TCon 采样周期），彻底消除残影。
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
            overlay.setBackgroundColor(Color.WHITE)
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { root.removeView(overlay) }
                view.invalidate()
            }, 90)
        }, 140)
    }
}
