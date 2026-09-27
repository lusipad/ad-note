package com.adnote.pdf

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import com.adnote.ink.StrokeGeometry
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
        val paint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val renderPath = Path()

        try {
            for ((index, page) in note.pages.withIndex()) {
                val pageInfo = PdfDocument.PageInfo.Builder(page.width, page.height, index + 1).create()
                val pdfDocPage = document.startPage(pageInfo)
                val canvas = pdfDocPage.canvas

                // 1. 绘制 PDF 页面底图
                val bmp = pdfRenderer.renderPage(index, page.width, page.height)
                if (bmp != null && !bmp.isRecycled) {
                    canvas.drawBitmap(bmp, null, Rect(0, 0, page.width, page.height), null)
                } else {
                    canvas.drawColor(Color.WHITE)
                }

                // 2. 绘制上层手写笔迹
                for (stroke in page.strokes) {
                    val outline = StrokeGeometry.outline(stroke)
                    if (outline.isNotEmpty()) {
                        renderPath.reset()
                        renderPath.moveTo(outline[0].x, outline[0].y)
                        for (i in 1 until outline.size) {
                            renderPath.lineTo(outline[i].x, outline[i].y)
                        }
                        renderPath.close()
                        canvas.drawPath(renderPath, paint)
                    } else if (stroke.points.isNotEmpty()) {
                        val pt = stroke.points[0]
                        val r = StrokeGeometry.widthAt(stroke.width, pt.pressure) / 2f
                        canvas.drawCircle(pt.x, pt.y, r, paint)
                    }
                }

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
