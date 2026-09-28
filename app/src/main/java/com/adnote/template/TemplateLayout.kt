package com.adnote.template

import com.adnote.model.PageTemplate
import com.adnote.model.PaperPresets
import kotlin.math.sqrt

/** 底纹图元。坐标为页面像素。 */
sealed interface TemplateShape

data class TemplateLine(
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val color: String, val width: Float, val dashed: Boolean = false,
) : TemplateShape

/** 仅描边的矩形。 */
data class TemplateRect(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val color: String, val width: Float,
) : TemplateShape

data class TemplateDot(val cx: Float, val cy: Float, val r: Float, val color: String) : TemplateShape

/**
 * 笔记本底纹的纯几何描述。
 *
 * 屏幕（Canvas）、导出的 PDF 与同步到 Obsidian 的 SVG 都由这一份图元列表绘制，
 * 保证三处外观完全一致；不依赖 Android，可直接做单元测试。
 */
object TemplateLayout {

    /** 以 1404 像素宽（文石 10.3 寸屏）为基准的缩放系数。 */
    fun density(width: Int): Float = (width.toFloat() / 1404f).coerceAtLeast(0.5f)

    /** 虚线的线段/间隔长度。 */
    fun dashIntervals(width: Int): FloatArray {
        val d = density(width)
        return floatArrayOf(5f * d, 4f * d)
    }

    private class Palette(isDark: Boolean) {
        val main = if (isDark) "#4B5563" else "#9CA3AF"
        val accent = if (isDark) "#374151" else "#C4C8D0"
        val dot = if (isDark) "#4B5563" else "#9CA3AF"
        val marginRed = if (isDark) "#7F1D1D" else "#EF4444"
        val strong = if (isDark) "#6B7280" else "#6B7280"
    }

    fun build(template: PageTemplate, backgroundHex: String, width: Int, height: Int): List<TemplateShape> {
        if (template == PageTemplate.BLANK || width <= 0 || height <= 0) return emptyList()
        val c = Palette(PaperPresets.find(backgroundHex).isDark)
        val d = density(width)
        val w = width.toFloat()
        val h = height.toFloat()
        val out = ArrayList<TemplateShape>()

        when (template) {
            PageTemplate.BLANK -> Unit

            PageTemplate.RULED, PageTemplate.RULED_WIDE, PageTemplate.RULED_NARROW -> {
                val spacing = when (template) {
                    PageTemplate.RULED_WIDE -> 74f * d
                    PageTemplate.RULED_NARROW -> 38f * d
                    else -> 54f * d
                }
                val top = 120f * d
                val bottom = 60f * d
                val left = 100f * d
                // 左侧装订辅助线
                out += TemplateLine(left, top - 20f * d, left, h - bottom, c.marginRed, 1.5f * d)
                var y = top
                while (y <= h - bottom) {
                    out += TemplateLine(40f * d, y, w - 40f * d, y, c.main, 1f * d)
                    y += spacing
                }
            }

            PageTemplate.GRID, PageTemplate.GRID_LARGE, PageTemplate.GRID_SMALL -> {
                val size = when (template) {
                    PageTemplate.GRID_LARGE -> 64f * d
                    PageTemplate.GRID_SMALL -> 28f * d
                    else -> 44f * d
                }
                grid(out, w, h, 40f * d, size) { k ->
                    if (k % 5 == 0) c.main to 1.2f * d else c.accent to 1f * d
                }
            }

            PageTemplate.ENGINEERING -> {
                // 坐标纸：细格 20，每 5 格一条加深主线
                grid(out, w, h, 40f * d, 20f * d) { k ->
                    if (k % 5 == 0) c.main to 1.3f * d else c.accent to 0.7f * d
                }
            }

            PageTemplate.DOT, PageTemplate.DOT_DENSE -> {
                val spacing = if (template == PageTemplate.DOT_DENSE) 28f * d else 44f * d
                val r = if (template == PageTemplate.DOT_DENSE) 1.2f * d else 1.5f * d
                val margin = 40f * d
                var y = margin
                while (y <= h - margin) {
                    var x = margin
                    while (x <= w - margin) {
                        out += TemplateDot(x, y, r, c.dot)
                        x += spacing
                    }
                    y += spacing
                }
            }

            PageTemplate.ISOMETRIC -> {
                // 等距点阵：奇数行错开半格，相邻三点构成等边三角形
                val spacing = 40f * d
                val rowH = spacing * sqrt(3f) / 2f
                val margin = 40f * d
                var y = margin
                var row = 0
                while (y <= h - margin) {
                    var x = margin + if (row % 2 == 1) spacing / 2f else 0f
                    while (x <= w - margin) {
                        out += TemplateDot(x, y, 1.5f * d, c.dot)
                        x += spacing
                    }
                    y += rowH
                    row++
                }
            }

            PageTemplate.TIANZI, PageTemplate.MIZI -> {
                val cell = 80f * d
                val cellGap = 10f * d
                val rowGap = 16f * d
                val cols = ((w - 80f * d) / (cell + cellGap)).toInt().coerceAtLeast(1)
                val rowW = cols * cell + (cols - 1) * cellGap
                val startX = (w - rowW) / 2f
                var y = 100f * d
                while (y + cell <= h - 60f * d) {
                    for (col in 0 until cols) {
                        val l = startX + col * (cell + cellGap)
                        val r = l + cell
                        val b = y + cell
                        val mx = l + cell / 2f
                        val my = y + cell / 2f
                        out += TemplateRect(l, y, r, b, c.main, 1.2f * d)
                        out += TemplateLine(l, my, r, my, c.accent, 0.8f * d, dashed = true)
                        out += TemplateLine(mx, y, mx, b, c.accent, 0.8f * d, dashed = true)
                        if (template == PageTemplate.MIZI) {
                            out += TemplateLine(l, y, r, b, c.accent, 0.8f * d, dashed = true)
                            out += TemplateLine(l, b, r, y, c.accent, 0.8f * d, dashed = true)
                        }
                    }
                    y += cell + rowGap
                }
            }

            PageTemplate.MANUSCRIPT -> {
                // 作文稿纸：每行 20 个相连方格，行间留窄条
                val margin = 60f * d
                val cols = 20
                val cell = (w - 2 * margin) / cols
                val rowGap = 14f * d
                var y = 100f * d
                while (y + cell <= h - 60f * d) {
                    out += TemplateRect(margin, y, margin + cols * cell, y + cell, c.marginRed, 1.2f * d)
                    for (col in 1 until cols) {
                        val x = margin + col * cell
                        out += TemplateLine(x, y, x, y + cell, c.marginRed, 0.8f * d)
                    }
                    y += cell + rowGap
                }
            }

            PageTemplate.PINYIN -> {
                val slot = 18f * d
                val stave = slot * 3f
                val gap = 36f * d
                val margin = 40f * d
                var y = 100f * d
                while (y + stave <= h - 60f * d) {
                    out += TemplateLine(margin, y, w - margin, y, c.accent, 0.8f * d, dashed = true)
                    out += TemplateLine(margin, y + slot, w - margin, y + slot, c.accent, 1f * d)
                    // 书写基准线（加深）
                    out += TemplateLine(margin, y + slot * 2f, w - margin, y + slot * 2f, c.strong, 1.5f * d)
                    out += TemplateLine(margin, y + stave, w - margin, y + stave, c.accent, 0.8f * d, dashed = true)
                    y += stave + gap
                }
            }

            PageTemplate.MUSIC -> {
                val spacing = 14f * d
                val staff = spacing * 4f
                val gap = 68f * d
                val margin = 40f * d
                var y = 100f * d
                while (y + staff <= h - 60f * d) {
                    for (i in 0..4) {
                        val ly = y + i * spacing
                        out += TemplateLine(margin, ly, w - margin, ly, c.main, 1f * d)
                    }
                    // 两侧终止线
                    out += TemplateLine(margin, y, margin, y + staff, c.main, 1.5f * d)
                    out += TemplateLine(w - margin, y, w - margin, y + staff, c.main, 1.5f * d)
                    y += staff + gap
                }
            }

            PageTemplate.CORNELL -> {
                val header = 110f * d
                val footer = 220f * d
                val cueW = w * 0.28f
                val margin = 40f * d
                val summaryY = h - footer
                out += TemplateLine(margin, header, w - margin, header, c.main, 2f * d)
                out += TemplateLine(margin, summaryY, w - margin, summaryY, c.main, 2f * d)
                out += TemplateLine(cueW, header, cueW, summaryY, c.main, 2f * d)
                val spacing = 50f * d
                var y = header + spacing
                while (y < summaryY - 10f) {
                    out += TemplateLine(cueW + 10f * d, y, w - margin, y, c.accent, 1f * d)
                    y += spacing
                }
                y = summaryY + spacing
                while (y < h - 30f * d) {
                    out += TemplateLine(margin, y, w - margin, y, c.accent, 1f * d)
                    y += spacing
                }
            }

            PageTemplate.TODO -> {
                // 待办清单：标题线 + 每行一个复选框
                val margin = 40f * d
                out += TemplateLine(margin, 110f * d, w - margin, 110f * d, c.main, 2f * d)
                val spacing = 64f * d
                val box = 26f * d
                var y = 110f * d + spacing
                while (y <= h - 60f * d) {
                    val boxLeft = margin + 20f * d
                    out += TemplateRect(boxLeft, y - box - 12f * d, boxLeft + box, y - 12f * d, c.strong, 1.5f * d)
                    out += TemplateLine(boxLeft + box + 24f * d, y, w - margin, y, c.main, 1f * d)
                    y += spacing
                }
            }
        }
        return out
    }

    /** 方格：[style] 根据线的序号（从 0 开始）返回颜色与线宽。 */
    private inline fun grid(
        out: MutableList<TemplateShape>, w: Float, h: Float, margin: Float, size: Float,
        style: (Int) -> Pair<String, Float>,
    ) {
        val minor = ArrayList<TemplateShape>()
        val major = ArrayList<TemplateShape>()
        var k = 0
        var x = margin
        while (x <= w - margin) {
            val (color, lw) = style(k)
            val target = if (k % 5 == 0) major else minor
            target += TemplateLine(x, margin, x, h - margin, color, lw)
            x += size; k++
        }
        k = 0
        var y = margin
        while (y <= h - margin) {
            val (color, lw) = style(k)
            val target = if (k % 5 == 0) major else minor
            target += TemplateLine(margin, y, w - margin, y, color, lw)
            y += size; k++
        }
        // 主线后画，避免被细线覆盖
        out += minor
        out += major
    }
}
