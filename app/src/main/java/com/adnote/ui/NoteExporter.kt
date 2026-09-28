package com.adnote.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.adnote.export.RemotePaths
import com.adnote.model.Note
import com.adnote.pdf.PdfPageRenderer
import java.io.File

/** 导出 PDF（整本，含 PDF 原文底图）或 PNG（单页），并调起系统分享。 */
object NoteExporter {

    fun exportDir(activity: Activity): File = File(activity.cacheDir, "exports").apply { mkdirs() }

    fun exportPdf(note: Note, assets: BitmapAssets, pdf: PdfPageRenderer?, out: File) {
        val doc = PdfDocument()
        val renderer = PageRenderer(assets)
        try {
            note.pages.forEachIndexed { index, page ->
                val info = PdfDocument.PageInfo.Builder(page.width, page.height, index + 1).create()
                val docPage = doc.startPage(info)
                val bg = pdf?.renderThumbnail(index, page.width, page.height)
                renderer.drawPage(docPage.canvas, page, bg)
                bg?.recycle()
                doc.finishPage(docPage)
            }
            out.parentFile?.mkdirs()
            out.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    fun exportPng(note: Note, index: Int, assets: BitmapAssets, pdf: PdfPageRenderer?, out: File) {
        val page = note.pages[index]
        val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        val bg = pdf?.renderThumbnail(index, page.width, page.height)
        PageRenderer(assets).drawPage(Canvas(bmp), page, bg)
        bg?.recycle()
        out.parentFile?.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    fun fileName(note: Note, suffix: String): String = RemotePaths.sanitize(note.title) + suffix

    fun share(activity: Activity, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, "分享 ${file.name}"))
    }
}
