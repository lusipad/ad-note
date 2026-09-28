package com.adnote.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.adnote.R
import com.adnote.model.PageTemplate
import com.adnote.model.PaperPresets

/**
 * 底纹 + 纸色选择器：分类胶囊、带缩略图的底纹卡片、纸色胶囊。
 * 新建笔记对话框和编辑器的「底纹与纸张」对话框共用。
 */
class TemplatePicker(
    private val context: Context,
    private val categoryContainer: LinearLayout,
    private val templateContainer: LinearLayout,
    private val colorContainer: LinearLayout,
    initialTemplate: PageTemplate,
    initialColor: String,
) {
    var selectedTemplate: PageTemplate = initialTemplate
        private set
    var selectedColor: String = PaperPresets.find(initialColor).hex
        private set

    private var selectedCategory: String =
        initialTemplate.category.takeIf { it in PageTemplate.CATEGORIES } ?: "全部"

    private val density = context.resources.displayMetrics.density
    private val thumbW = (64 * density).toInt()
    private val thumbH = thumbW * 4 / 3
    private val thumbs = HashMap<String, Bitmap>()

    init {
        refreshCategories()
        refreshTemplates()
        refreshColors()
    }

    private fun refreshCategories() {
        categoryContainer.removeAllViews()
        PageTemplate.CATEGORIES.forEach { cat ->
            categoryContainer.addView(Chips.text(context, cat, cat == selectedCategory) {
                selectedCategory = cat
                refreshCategories()
                refreshTemplates()
            })
        }
    }

    private fun refreshTemplates() {
        templateContainer.removeAllViews()
        PageTemplate.inCategory(selectedCategory).forEach { template ->
            templateContainer.addView(templateCard(template, template == selectedTemplate))
        }
    }

    private fun refreshColors() {
        colorContainer.removeAllViews()
        PaperPresets.ALL.forEach { tone ->
            colorContainer.addView(
                Chips.color(context, tone.hex, tone.displayName, tone.hex.equals(selectedColor, true)) {
                    selectedColor = tone.hex
                    refreshColors()
                    refreshTemplates()
                }
            )
        }
    }

    private fun templateCard(template: PageTemplate, selected: Boolean): LinearLayout {
        val pad = (6 * density).toInt()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * density).toInt() }
            setBackgroundResource(if (selected) R.drawable.bg_tool_selected else R.drawable.bg_button_secondary)

            addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(thumbW, thumbH)
                setImageBitmap(thumbnail(template))
                scaleType = ImageView.ScaleType.FIT_XY
            })
            addView(TextView(context).apply {
                text = template.displayName
                textSize = 11f
                setTextColor(context.getColor(R.color.text_primary))
                gravity = Gravity.CENTER
                setPadding(0, (4 * density).toInt(), 0, 0)
            })
            contentDescription = template.displayName
            setOnClickListener {
                selectedTemplate = template
                refreshTemplates()
            }
        }
    }

    /** 以 1404×1872 标准页面渲染后缩小，线条疏密比例与真实页面一致。 */
    private fun thumbnail(template: PageTemplate): Bitmap = thumbs.getOrPut("${template.name}|$selectedColor") {
        val bmp = Bitmap.createBitmap(thumbW, thumbH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val scale = thumbW / PAGE_W.toFloat()
        canvas.scale(scale, thumbH / PAGE_H.toFloat())
        PageTemplateRenderer.render(canvas, template, selectedColor, PAGE_W, PAGE_H, minLine = 1f / scale)
        bmp
    }

    companion object {
        private const val PAGE_W = 1404
        private const val PAGE_H = 1872
    }
}
