package com.adnote.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.adnote.storage.NoteRepository
import java.io.File

/** 把用户选择的图片/PDF 复制进笔记目录，按需缩小并修正拍照方向。 */
object ImageImporter {

    data class Imported(val path: String, val width: Int, val height: Int)

    /** 插图：最长边不超过 [maxSide]，有透明度的 PNG 保留 PNG，其余转 JPEG。 */
    fun importImage(
        context: Context, uri: Uri, repo: NoteRepository, noteId: String, maxSide: Int = 1600,
    ): Imported {
        val bmp = decode(context, uri, maxSide) ?: throw IllegalStateException("无法读取图片")
        val png = bmp.hasAlpha()
        val path = repo.newAssetPath("images", if (png) "png" else "jpg")
        write(bmp, repo.assetFile(noteId, path), png)
        return Imported(path, bmp.width, bmp.height).also { bmp.recycle() }
    }

    /** 自定义背景：图片，或 PDF 的第一页。 */
    fun importBackground(context: Context, uri: Uri, repo: NoteRepository, noteId: String): Imported {
        val mime = context.contentResolver.getType(uri).orEmpty()
        val bmp = if (mime == "application/pdf" || uri.toString().endsWith(".pdf", ignoreCase = true)) {
            renderPdfFirstPage(context, uri)
        } else {
            decode(context, uri, 2048)
        } ?: throw IllegalStateException("无法读取背景文件")
        val path = repo.newAssetPath("backgrounds", "png")
        write(bmp, repo.assetFile(noteId, path), png = true)
        return Imported(path, bmp.width, bmp.height).also { bmp.recycle() }
    }

    private fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        var bmp = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        val rotation = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)

        val longest = maxOf(bmp.width, bmp.height)
        val scale = if (longest > maxSide) maxSide.toFloat() / longest else 1f
        if (rotation != 0f || scale < 1f) {
            val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
            val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (out !== bmp) bmp.recycle()
            bmp = out
        }
        return bmp
    }

    private fun renderPdfFirstPage(context: Context, uri: Uri): Bitmap? {
        val tmp = File.createTempFile("bg_", ".pdf", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                ?: return null
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { r ->
                    if (r.pageCount == 0) return null
                    r.openPage(0).use { page ->
                        val w = 1404
                        val h = (w.toLong() * page.height / page.width).toInt()
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        return bmp
                    }
                }
            }
        } finally {
            tmp.delete()
        }
    }

    private fun write(bmp: Bitmap, file: File, png: Boolean) {
        file.parentFile?.mkdirs()
        file.outputStream().use {
            bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, it)
        }
    }
}
