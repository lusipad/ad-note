package com.adnote.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.adnote.model.Page
import com.adnote.model.PageTemplate
import com.adnote.model.PaperPresets
import java.util.Locale

/**
 * 笔记本底质模板渲染器。
 * 负责在 Android Canvas（屏幕/PDF）上绘制纸张底质（横线、方格、点阵、康奈尔），
 * 并提供对应的 SVG 矢量代码生成，供 Obsidian / WebDAV 保持完全一致的视觉排版。
 */
object PageTemplateRenderer {

    private val linePaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val dashedPaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    /**
     * 在 Canvas 上绘制纸张底色与笔记本底质模版。
     */
    fun render(
        canvas: Canvas,
        template: PageTemplate,
        backgroundColorHex: String,
        width: Int,
        height: Int
    ) {
        val paper = PaperPresets.find(backgroundColorHex)
        val bgColor = parseColorSafe(paper.hex, Color.WHITE)
        canvas.drawColor(bgColor)

        if (template == PageTemplate.BLANK) return

        val isDark = paper.isDark
        val mainLineColor = if (isDark) Color.parseColor("#374151") else Color.parseColor("#D1D5DB")
        val accentLineColor = if (isDark) Color.parseColor("#4B5563") else Color.parseColor("#E5E7EB")
        val dotColor = if (isDark) Color.parseColor("#4B5563") else Color.parseColor("#9CA3AF")
        val marginRedColor = if (isDark) Color.parseColor("#7F1D1D") else Color.parseColor("#FCA5A5")

        val density = (width.toFloat() / 1404f).coerceAtLeast(0.5f)

        when (template) {
            PageTemplate.BLANK -> Unit

            PageTemplate.RULED, PageTemplate.RULED_WIDE, PageTemplate.RULED_NARROW -> {
                val lineSpacing = when (template) {
                    PageTemplate.RULED_WIDE -> 74f * density
                    PageTemplate.RULED_NARROW -> 38f * density
                    else -> 54f * density
                }
                val topMargin = 120f * density
                val bottomMargin = 60f * density
                val leftMargin = 100f * density

                // 左侧装订辅助线
                linePaint.color = marginRedColor
                linePaint.strokeWidth = 1.5f * density
                canvas.drawLine(leftMargin, topMargin - 20f * density, leftMargin, height - bottomMargin, linePaint)

                // 横向格线
                linePaint.color = mainLineColor
                linePaint.strokeWidth = 1.0f * density
                var y = topMargin
                while (y <= height - bottomMargin) {
                    canvas.drawLine(40f * density, y, width - 40f * density, y, linePaint)
                    y += lineSpacing
                }
            }

            PageTemplate.GRID, PageTemplate.GRID_LARGE, PageTemplate.GRID_SMALL -> {
                val gridSize = when (template) {
                    PageTemplate.GRID_LARGE -> 64f * density
                    PageTemplate.GRID_SMALL -> 28f * density
                    else -> 44f * density
                }
                linePaint.color = accentLineColor
                linePaint.strokeWidth = 1.0f * density
                val marginX = 40f * density
                val marginY = 40f * density

                var x = marginX
                while (x <= width - marginX) {
                    canvas.drawLine(x, marginY, x, height - marginY, linePaint)
                    x += gridSize
                }
                var y = marginY
                while (y <= height - marginY) {
                    canvas.drawLine(marginX, y, width - marginX, y, linePaint)
                    y += gridSize
                }
            }

            PageTemplate.DOT, PageTemplate.DOT_DENSE -> {
                val dotSpacing = if (template == PageTemplate.DOT_DENSE) 28f * density else 44f * density
                val dotRadius = if (template == PageTemplate.DOT_DENSE) 1.2f * density else 1.5f * density
                fillPaint.color = dotColor
                val marginX = 40f * density
                val marginY = 40f * density

                var y = marginY
                while (y <= height - marginY) {
                    var x = marginX
                    while (x <= width - marginX) {
                        canvas.drawCircle(x, y, dotRadius, fillPaint)
                        x += dotSpacing
                    }
                    y += dotSpacing
                }
            }

            PageTemplate.TIANZI, PageTemplate.MIZI -> {
                val cellSize = 80f * density
                val cellGap = 10f * density
                val rowGap = 16f * density
                val marginY = 100f * density
                val usableW = width - 80f * density
                val cols = (usableW / (cellSize + cellGap)).toInt().coerceAtLeast(1)
                val totalRowW = cols * cellSize + (cols - 1) * cellGap
                val startX = (width - totalRowW) / 2f

                linePaint.color = mainLineColor
                linePaint.strokeWidth = 1.2f * density

                dashedPaint.color = accentLineColor
                dashedPaint.strokeWidth = 0.8f * density
                dashedPaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)

                var y = marginY
                while (y + cellSize <= height - 60f * density) {
                    for (c in 0 until cols) {
                        val left = startX + c * (cellSize + cellGap)
                        val right = left + cellSize
                        val top = y
                        val bottom = top + cellSize
                        val midX = left + cellSize / 2f
                        val midY = top + cellSize / 2f

                        // 外框实线
                        canvas.drawRect(left, top, right, bottom, linePaint)
                        // 十字虚线
                        canvas.drawLine(left, midY, right, midY, dashedPaint)
                        canvas.drawLine(midX, top, midX, bottom, dashedPaint)

                        // 米字格对角线
                        if (template == PageTemplate.MIZI) {
                            canvas.drawLine(left, top, right, bottom, dashedPaint)
                            canvas.drawLine(left, bottom, right, top, dashedPaint)
                        }
                    }
                    y += cellSize + rowGap
                }
            }

            PageTemplate.PINYIN -> {
                val slotH = 18f * density
                val staveH = slotH * 3f
                val staveGap = 36f * density
                val marginX = 40f * density
                val startY = 100f * density

                dashedPaint.color = accentLineColor
                dashedPaint.strokeWidth = 0.8f * density
                dashedPaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)

                var y = startY
                while (y + staveH <= height - 60f * density) {
                    // Line 1: 上部虚线
                    canvas.drawLine(marginX, y, width - marginX, y, dashedPaint)

                    // Line 2: 主体上缘实线
                    linePaint.color = accentLineColor
                    linePaint.strokeWidth = 1.0f * density
                    canvas.drawLine(marginX, y + slotH, width - marginX, y + slotH, linePaint)

                    // Line 3: 书写基准线 (加深)
                    linePaint.color = if (isDark) Color.parseColor("#4B5563") else Color.parseColor("#9CA3AF")
                    linePaint.strokeWidth = 1.5f * density
                    canvas.drawLine(marginX, y + slotH * 2f, width - marginX, y + slotH * 2f, linePaint)

                    // Line 4: 下部延展虚线
                    canvas.drawLine(marginX, y + staveH, width - marginX, y + staveH, dashedPaint)

                    y += staveH + staveGap
                }
            }

            PageTemplate.MUSIC -> {
                val lineSpacing = 14f * density
                val staffH = lineSpacing * 4f
                val staffGap = 68f * density
                val marginX = 40f * density
                val startY = 100f * density

                linePaint.color = mainLineColor
                linePaint.strokeWidth = 1.0f * density

                var y = startY
                while (y + staffH <= height - 60f * density) {
                    for (i in 0..4) {
                        val ly = y + i * lineSpacing
                        canvas.drawLine(marginX, ly, width - marginX, ly, linePaint)
                    }
                    // 两侧终止线
                    canvas.drawLine(marginX, y, marginX, y + staffH, linePaint)
                    canvas.drawLine(width - marginX, y, width - marginX, y + staffH, linePaint)
                    y += staffH + staffGap
                }
            }

            PageTemplate.CORNELL -> {
                val headerH = 110f * density
                val footerH = 220f * density
                val cueW = width * 0.28f
                val marginX = 40f * density

                linePaint.color = mainLineColor
                linePaint.strokeWidth = 2.0f * density

                // 顶部分隔线
                canvas.drawLine(marginX, headerH, width - marginX, headerH, linePaint)
                // 底部分隔线
                val summaryY = height - footerH
                canvas.drawLine(marginX, summaryY, width - marginX, summaryY, linePaint)
                // 垂直栏目分割线
                canvas.drawLine(cueW, headerH, cueW, summaryY, linePaint)

                // 右侧笔记区横线
                linePaint.strokeWidth = 1.0f * density
                linePaint.color = accentLineColor
                val lineSpacing = 50f * density
                var y = headerH + lineSpacing
                while (y < summaryY - 10f) {
                    canvas.drawLine(cueW + 10f * density, y, width - marginX, y, linePaint)
                    y += lineSpacing
                }

                // 底栏总结区横线
                y = summaryY + lineSpacing
                while (y < height - 30f * density) {
                    canvas.drawLine(marginX, y, width - marginX, y, linePaint)
                    y += lineSpacing
                }
            }
        }
    }

    /**
     * 生成对应的 SVG 底质代码片段，嵌入在 <svg> 根节点下。
     */
    fun generateSvgTemplate(page: Page): String {
        if (page.template == PageTemplate.BLANK) return ""

        val paper = PaperPresets.find(page.backgroundColor)
        val isDark = paper.isDark
        val mainLineHex = if (isDark) "#374151" else "#D1D5DB"
        val accentLineHex = if (isDark) "#4B5563" else "#E5E7EB"
        val dotHex = if (isDark) "#4B5563" else "#9CA3AF"
        val marginRedHex = if (isDark) "#7F1D1D" else "#FCA5A5"

        val width = page.width
        val height = page.height
        val density = (width.toFloat() / 1404f).coerceAtLeast(0.5f)

        return buildString {
            append("""<!-- adnote template: ${page.template.name} -->""").append('\n')
            append("""<g id="template_${page.template.name.lowercase()}" opacity="0.85">""").append('\n')

            when (page.template) {
                PageTemplate.BLANK -> Unit

                PageTemplate.RULED, PageTemplate.RULED_WIDE, PageTemplate.RULED_NARROW -> {
                    val lineSpacing = when (page.template) {
                        PageTemplate.RULED_WIDE -> 74f * density
                        PageTemplate.RULED_NARROW -> 38f * density
                        else -> 54f * density
                    }
                    val topMargin = 120f * density
                    val bottomMargin = 60f * density
                    val leftMargin = 100f * density

                    // 竖向辅助线
                    append("""  <line x1="${f(leftMargin)}" y1="${f(topMargin - 20f * density)}" x2="${f(leftMargin)}" y2="${f(height - bottomMargin)}" stroke="$marginRedHex" stroke-width="1.5"/>""").append('\n')

                    // 横向线条
                    var y = topMargin
                    while (y <= height - bottomMargin) {
                        append("""  <line x1="${f(40f * density)}" y1="${f(y)}" x2="${f(width - 40f * density)}" y2="${f(y)}" stroke="$mainLineHex" stroke-width="1"/>""").append('\n')
                        y += lineSpacing
                    }
                }

                PageTemplate.GRID, PageTemplate.GRID_LARGE, PageTemplate.GRID_SMALL -> {
                    val gridSize = when (page.template) {
                        PageTemplate.GRID_LARGE -> 64f * density
                        PageTemplate.GRID_SMALL -> 28f * density
                        else -> 44f * density
                    }
                    val marginX = 40f * density
                    val marginY = 40f * density

                    var x = marginX
                    while (x <= width - marginX) {
                        append("""  <line x1="${f(x)}" y1="${f(marginY)}" x2="${f(x)}" y2="${f(height - marginY)}" stroke="$accentLineHex" stroke-width="1"/>""").append('\n')
                        x += gridSize
                    }
                    var y = marginY
                    while (y <= height - marginY) {
                        append("""  <line x1="${f(marginX)}" y1="${f(y)}" x2="${f(width - marginX)}" y2="${f(y)}" stroke="$accentLineHex" stroke-width="1"/>""").append('\n')
                        y += gridSize
                    }
                }

                PageTemplate.DOT, PageTemplate.DOT_DENSE -> {
                    val dotSpacing = if (page.template == PageTemplate.DOT_DENSE) 28f * density else 44f * density
                    val dotRadius = if (page.template == PageTemplate.DOT_DENSE) 1.2f * density else 1.5f * density
                    val marginX = 40f * density
                    val marginY = 40f * density

                    var y = marginY
                    while (y <= height - marginY) {
                        var x = marginX
                        while (x <= width - marginX) {
                            append("""  <circle cx="${f(x)}" cy="${f(y)}" r="${f(dotRadius)}" fill="$dotHex"/>""").append('\n')
                            x += dotSpacing
                        }
                        y += dotSpacing
                    }
                }

                PageTemplate.TIANZI, PageTemplate.MIZI -> {
                    val cellSize = 80f * density
                    val cellGap = 10f * density
                    val rowGap = 16f * density
                    val marginY = 100f * density
                    val usableW = width - 80f * density
                    val cols = (usableW / (cellSize + cellGap)).toInt().coerceAtLeast(1)
                    val totalRowW = cols * cellSize + (cols - 1) * cellGap
                    val startX = (width - totalRowW) / 2f

                    var y = marginY
                    while (y + cellSize <= height - 60f * density) {
                        for (c in 0 until cols) {
                            val left = startX + c * (cellSize + cellGap)
                            val right = left + cellSize
                            val top = y
                            val bottom = top + cellSize
                            val midX = left + cellSize / 2f
                            val midY = top + cellSize / 2f

                            append("""  <rect x="${f(left)}" y="${f(top)}" width="${f(cellSize)}" height="${f(cellSize)}" stroke="$mainLineHex" stroke-width="1.2" fill="none"/>""").append('\n')
                            append("""  <line x1="${f(left)}" y1="${f(midY)}" x2="${f(right)}" y2="${f(midY)}" stroke="$accentLineHex" stroke-width="0.8" stroke-dasharray="5,4"/>""").append('\n')
                            append("""  <line x1="${f(midX)}" y1="${f(top)}" x2="${f(midX)}" y2="${f(bottom)}" stroke="$accentLineHex" stroke-width="0.8" stroke-dasharray="5,4"/>""").append('\n')

                            if (page.template == PageTemplate.MIZI) {
                                append("""  <line x1="${f(left)}" y1="${f(top)}" x2="${f(right)}" y2="${f(bottom)}" stroke="$accentLineHex" stroke-width="0.8" stroke-dasharray="5,4"/>""").append('\n')
                                append("""  <line x1="${f(left)}" y1="${f(bottom)}" x2="${f(right)}" y2="${f(top)}" stroke="$accentLineHex" stroke-width="0.8" stroke-dasharray="5,4"/>""").append('\n')
                            }
                        }
                        y += cellSize + rowGap
                    }
                }

                PageTemplate.PINYIN -> {
                    val slotH = 18f * density
                    val staveH = slotH * 3f
                    val staveGap = 36f * density
                    val marginX = 40f * density
                    val startY = 100f * density
                    val baseLineHex = if (isDark) "#4B5563" else "#9CA3AF"

                    var y = startY
                    while (y + staveH <= height - 60f * density) {
                        append("""  <line x1="${f(marginX)}" y1="${f(y)}" x2="${f(width - marginX)}" y2="${f(y)}" stroke="$accentLineHex" stroke-width="0.8" stroke-dasharray="5,4"/>""").append('\n')
                        append("""  <line x1="${f(marginX)}" y1="${f(y + slotH)}" x2="${f(width - marginX)}" y2="${f(y + slotH)}" stroke="$accentLineHex" stroke-width="1"/>""").append('\n')
                        append("""  <line x1="${f(marginX)}" y1="${f(y + slotH * 2f)}" x2="${f(width - marginX)}" y2="${f(y + slotH * 2f)}" stroke="$baseLineHex" stroke-width="1.5"/>""").append('\n')
                        append("""  <line x1="${f(marginX)}" y1="${f(y + staveH)}" x2="${f(width - marginX)}" y2="${f(y + staveH)}" stroke="$accentLineHex" stroke-width="0.8" stroke-dasharray="5,4"/>""").append('\n')
                        y += staveH + staveGap
                    }
                }

                PageTemplate.MUSIC -> {
                    val lineSpacing = 14f * density
                    val staffH = lineSpacing * 4f
                    val staffGap = 68f * density
                    val marginX = 40f * density
                    val startY = 100f * density

                    var y = startY
                    while (y + staffH <= height - 60f * density) {
                        for (i in 0..4) {
                            val ly = y + i * lineSpacing
                            append("""  <line x1="${f(marginX)}" y1="${f(ly)}" x2="${f(width - marginX)}" y2="${f(ly)}" stroke="$mainLineHex" stroke-width="1"/>""").append('\n')
                        }
                        append("""  <line x1="${f(marginX)}" y1="${f(y)}" x2="${f(marginX)}" y2="${f(y + staffH)}" stroke="$mainLineHex" stroke-width="1.5"/>""").append('\n')
                        append("""  <line x1="${f(width - marginX)}" y1="${f(y)}" x2="${f(width - marginX)}" y2="${f(y + staffH)}" stroke="$mainLineHex" stroke-width="1.5"/>""").append('\n')
                        y += staffH + staffGap
                    }
                }

                PageTemplate.CORNELL -> {
                    val headerH = 110f * density
                    val footerH = 220f * density
                    val cueW = width * 0.28f
                    val marginX = 40f * density
                    val summaryY = height - footerH

                    append("""  <line x1="${f(marginX)}" y1="${f(headerH)}" x2="${f(width - marginX)}" y2="${f(headerH)}" stroke="$mainLineHex" stroke-width="2"/>""").append('\n')
                    append("""  <line x1="${f(marginX)}" y1="${f(summaryY)}" x2="${f(width - marginX)}" y2="${f(summaryY)}" stroke="$mainLineHex" stroke-width="2"/>""").append('\n')
                    append("""  <line x1="${f(cueW)}" y1="${f(headerH)}" x2="${f(cueW)}" y2="${f(summaryY)}" stroke="$mainLineHex" stroke-width="2"/>""").append('\n')

                    val lineSpacing = 50f * density
                    var y = headerH + lineSpacing
                    while (y < summaryY - 10f) {
                        append("""  <line x1="${f(cueW + 10f * density)}" y1="${f(y)}" x2="${f(width - marginX)}" y2="${f(y)}" stroke="$accentLineHex" stroke-width="1"/>""").append('\n')
                        y += lineSpacing
                    }
                    y = summaryY + lineSpacing
                    while (y < height - 30f * density) {
                        append("""  <line x1="${f(marginX)}" y1="${f(y)}" x2="${f(width - marginX)}" y2="${f(y)}" stroke="$accentLineHex" stroke-width="1"/>""").append('\n')
                        y += lineSpacing
                    }
                }
            }

            append("</g>\n")
        }
    }

    private fun parseColorSafe(hex: String, defaultColor: Int): Int {
        return try {
            Color.parseColor(hex)
        } catch (_: Exception) {
            defaultColor
        }
    }

    private fun f(v: Float): String = String.format(Locale.ROOT, "%.1f", v)
}
