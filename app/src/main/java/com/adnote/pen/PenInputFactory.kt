package com.adnote.pen

import android.util.Log

object PenInputFactory {

    private const val TAG = "PenInputFactory"

    var activeInputName: String = "未初始化"
        private set

    /**
     * 创建画笔通道。
     * @param preferOnyx 是否优先尝试文石低延迟硬件通道
     * @param stylusOnly 是否开启仅手写笔（防误触）模式
     */
    fun create(preferOnyx: Boolean = true, stylusOnly: Boolean = false): PenInput {
        val deviceInfo = DeviceDetector.detect()
        val isOnyxDevice = deviceInfo.brand == DeviceBrand.ONYX

        if (preferOnyx && isOnyxDevice) {
            try {
                val onyx = OnyxPenInput()
                activeInputName = onyx.name
                Log.i(TAG, "已检测到文石/得到设备，启用文石低延迟手写通道 (OnyxPenInput)")
                return onyx
            } catch (t: Throwable) {
                Log.w(TAG, "OnyxPenInput 不可用，平滑降级至标准触控通道: ${t.message}")
            }
        }

        val fallback = MotionPenInput(stylusOnly = stylusOnly)
        activeInputName = fallback.name
        Log.i(TAG, "已启用标准触控通道 (MotionPenInput, stylusOnly=$stylusOnly, brand=${deviceInfo.brand})")
        return fallback
    }
}
