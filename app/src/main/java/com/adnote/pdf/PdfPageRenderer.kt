package com.adnote.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.Closeable
import java.io.File
import kotlin.math.roundToInt

class PdfPageRenderer(private val pdfFile: File) : Closeable {

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    val pageCount: Int
        get() = renderer?.pageCount ?: 0

    // 内存中缓存最近 4 页的渲染图，保证翻页秒开且不爆内存
    private val bitmapCache = object : LruCache<Int, Bitmap>(4) {
        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted && oldValue != newValue && !oldValue.isRecycled) {
                oldValue.recycle()
            }
        }
    }

    init {
        openRenderer()
    }

    private fun openRenderer() {
        if (!pdfFile.exists()) return
        fileDescriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        fileDescriptor?.let {
            renderer = PdfRenderer(it)
        }
    }

    /**
     * 获取指定页的原始尺寸（宽, 高）
     */
    @Synchronized
    fun getPageDimensions(pageIndex: Int): Pair<Int, Int>? {
        val r = renderer ?: return null
        if (pageIndex !in 0 until r.pageCount) return null
        val page = r.openPage(pageIndex)
        val dim = Pair(page.width, page.height)
        page.close()
        return dim
    }

    /**
     * 渲染指定页为高质量 Bitmap（带缓存）。
     * @param targetWidth 渲染目标宽度（像素）
     * @param targetHeight 渲染目标高度（像素）
     */
    @Synchronized
    fun renderPage(pageIndex: Int, targetWidth: Int, targetHeight: Int): Bitmap? {
        val r = renderer ?: return null
        if (pageIndex !in 0 until r.pageCount) return null

        val cached = bitmapCache.get(pageIndex)
        if (cached != null && !cached.isRecycled && cached.width == targetWidth && cached.height == targetHeight) {
            return cached
        }

        val page = r.openPage(pageIndex)
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)

        val transform = Matrix().apply {
            val scaleX = targetWidth.toFloat() / page.width.toFloat()
            val scaleY = targetHeight.toFloat() / page.height.toFloat()
            setScale(scaleX, scaleY)
        }

        page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()

        bitmapCache.put(pageIndex, bitmap)
        return bitmap
    }

    /**
     * 渲染一张不进缓存的小图（页面概览缩略图）。
     * 不能复用 [renderPage]：缩略图会把正在显示的大图挤出 LRU 缓存并被回收。
     */
    @Synchronized
    fun renderThumbnail(pageIndex: Int, targetWidth: Int, targetHeight: Int): Bitmap? {
        val r = renderer ?: return null
        if (pageIndex !in 0 until r.pageCount) return null
        val page = r.openPage(pageIndex)
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val transform = Matrix().apply {
            setScale(targetWidth.toFloat() / page.width, targetHeight.toFloat() / page.height)
        }
        page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        return bitmap
    }

    @Synchronized
    override fun close() {
        bitmapCache.evictAll()
        runCatching { renderer?.close() }
        runCatching { fileDescriptor?.close() }
        renderer = null
        fileDescriptor = null
    }
}
