package com.adnote.pen

import android.util.Log

object PenInputFactory {

    private const val TAG = "PenInputFactory"

    var activeInputName: String = "未初始化"
        private set

    fun create(): PenInput {
        return try {
            val onyx = OnyxPenInput()
            activeInputName = onyx.name
            Log.i(TAG, "已启用文石低延迟手写通道 (OnyxPenInput)")
            onyx
        } catch (t: Throwable) {
            Log.w(TAG, "OnyxPenInput 不可用，回退至普通触控通道 (MotionPenInput): ${t.message}")
            val fallback = MotionPenInput()
            activeInputName = fallback.name
            fallback
        }
    }
}
