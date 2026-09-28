package com.adnote.pdf

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import com.adnote.model.Note
import java.io.File
import java.io.FileOutputStream

object PdfExporter {

    /**
     * 将笔记中的手写笔画压合到底层 PDF，输出一份带手写批注的新 PDF 文件。
     */
    fun exportAnnotatedPdf(
        note: Note,
        pdfRenderer: PdfPageRenderer,
        outputFile: File,
    ) {
        val document = PdfDocument()
        val painter = com.adnote.ui.StrokePainter()

        try {
            for ((index, page) in note.pages.withIndex()) {
                val pageInfo = PdfDocument.PageInfo.Builder(page.width, page.height, index + 1).create()
                val pdfDocPage = document.startPage(pageInfo)
                val canvas = pdfDocPage.canvas

                // 1. 绘制 PDF 页面底图或笔记本底质模板
                val bmp = pdfRenderer.renderPage(index, page.width, page.height)
                if (bmp != null && !bmp.isRecycled) {
                    canvas.drawBitmap(bmp, null, Rect(0, 0, page.width, page.height), null)
                } else {
                    com.adnote.ui.PageTemplateRenderer.render(canvas, page.template, page.backgroundColor, page.width, page.height)
                }

                // 2. 绘制上层手写笔迹
                painter.drawAll(canvas, page.strokes)

                document.finishPage(pdfDocPage)
            }

            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { out ->
                document.writeTo(out)
            }
        } finally {
            document.close()
        }
    }
}
