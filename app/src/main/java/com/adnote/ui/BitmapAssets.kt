package com.adnote.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File

/**
 * 笔记附件图片（插图、自定义背景）的解码缓存。按需降采样，最长边不超过 [maxSide]。
 * 可在后台线程（缩略图、导出）调用。
 */
class BitmapAssets(private val noteDir: File, private val maxSide: Int = 2048) {

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    @Synchronized
    fun get(relPath: String): Bitmap? {
        cache.get(relPath)?.let { if (!it.isRecycled) return it }
        val file = File(noteDir, relPath)
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        cache.put(relPath, bmp)
        return bmp
    }
}
