package com.adnote.pen

import android.view.View
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode

object EinkRefresher {

    /**
     * 全刷屏幕以消除墨水屏残影。
     * 若在非文石设备或不支持 EpdController 的环境，安全回退到普通 invalidate。
     */
    fun fullRefresh(view: View) {
        val success = runCatching {
            EpdController.refreshScreen(view, UpdateMode.GC)
            true
        }.recoverCatching {
            EpdController.repaintEveryThing(UpdateMode.GC)
            true
        }.getOrDefault(false)

        if (!success) {
            view.invalidate()
        }
    }
}
