package com.adnote.pdf

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.adnote.model.Note
import com.adnote.model.Page
import com.adnote.storage.NoteRepository
import java.io.File
import java.io.FileOutputStream

object PdfImporter {

    /**
     * 从系统 Uri 导入 PDF 文件并生成对应的 Note。
     */
    fun importPdfFromUri(
        context: Context,
        uri: Uri,
        repository: NoteRepository,
        customTitle: String? = null,
        folder: String = Note.DEFAULT_FOLDER,
        scaleFactor: Float = 2.0f,
    ): Result<Note> = runCatching {
        val resolver = context.contentResolver

        // 1. 获取文件名
        val fileName = customTitle?.ifBlank { null } ?: queryFileName(context, uri) ?: "导入文档"
        val cleanTitle = fileName.removeSuffix(".pdf").removeSuffix(".PDF")

        // 2. 复制到临时文件以读取 PDF 属性
        val tempFile = File.createTempFile("pdf_import_", ".tmp", context.cacheDir)
        try {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("无法打开 PDF 文件流")

            // 3. 读取 PDF 总页数与各页尺寸
            val pages = ArrayList<Page>()
            val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd.use {
                val renderer = PdfRenderer(it)
                renderer.use { r ->
                    val count = r.pageCount
                    if (count <= 0) throw IllegalStateException("PDF 文件没有有效页面")

                    for (i in 0 until count) {
                        val pdfPage = r.openPage(i)
                        val w = (pdfPage.width * scaleFactor).toInt().coerceAtLeast(800)
                        val h = (pdfPage.height * scaleFactor).toInt().coerceAtLeast(1000)
                        pdfPage.close()
                        pages.add(Page(width = w, height = h))
                    }
                }
            }

            // 4. 将 PDF 写入笔记专属沙盒，持久化 Note
            tempFile.inputStream().use { input ->
                repository.createPdfNote(
                    title = cleanTitle,
                    folder = folder,
                    pdfSource = input,
                    pages = pages
                )
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) return cursor.getString(index)
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}
