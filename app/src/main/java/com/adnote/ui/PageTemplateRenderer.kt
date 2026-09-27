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

            PageTemplate.RULED -> {
                // 横格纸：顶部留白 + 左侧竖向边距装订线 + 横线
                val topMargin = 120f * density
                val bottomMargin = 60f * density
                val leftMargin = 100f * density
                val lineSpacing = 56f * density

                // 左侧辅助线
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

            PageTemplate.GRID -> {
                // 方格纸：44px 间距精细方格网
                val gridSize = 44f * density
                linePaint.color = accentLineColor
                linePaint.strokeWidth = 1.0f * density

                val marginX = 40f * density
                val marginY = 40f * density

                // 竖线
                var x = marginX
                while (x <= width - marginX) {
                    canvas.drawLine(x, marginY, x, height - marginY, linePaint)
                    x += gridSize
                }
                // 横线
                var y = marginY
                while (y <= height - marginY) {
                    canvas.drawLine(marginX, y, width - marginX, y, linePaint)
                    y += gridSize
                }
            }

            PageTemplate.DOT -> {
                // 点阵纸：44px 点距，半径 1.5px 圆点
                val dotSpacing = 44f * density
                val dotRadius = 1.5f * density
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

            PageTemplate.CORNELL -> {
                // 康奈尔笔记法：顶栏（Title/Date） + 左线索栏（28%） + 右笔记横线栏 + 底总结栏
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

                PageTemplate.RULED -> {
                    val topMargin = 120f * density
                    val bottomMargin = 60f * density
                    val leftMargin = 100f * density
                    val lineSpacing = 56f * density

                    // 竖向辅助线
                    append("""  <line x1="${f(leftMargin)}" y1="${f(topMargin - 20f * density)}" x2="${f(leftMargin)}" y2="${f(height - bottomMargin)}" stroke="$marginRedHex" stroke-width="1.5"/>""").append('\n')

                    // 横向线条
                    var y = topMargin
                    while (y <= height - bottomMargin) {
                        append("""  <line x1="${f(40f * density)}" y1="${f(y)}" x2="${f(width - 40f * density)}" y2="${f(y)}" stroke="$mainLineHex" stroke-width="1"/>""").append('\n')
                        y += lineSpacing
                    }
                }

                PageTemplate.GRID -> {
                    val gridSize = 44f * density
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

                PageTemplate.DOT -> {
                    val dotSpacing = 44f * density
                    val dotRadius = 1.5f * density
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
