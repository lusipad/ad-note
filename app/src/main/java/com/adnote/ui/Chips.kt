package com.adnote.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.adnote.R

/** 对话框与工具栏里反复用到的胶囊按钮、色块（针对黑白墨水屏增加汉字色彩标识与双环选中态）。 */
object Chips {

    private fun dp(context: Context, v: Float): Int = (v * context.resources.displayMetrics.density).toInt()

    /** 根据色值返回单字中文简称，解决黑白墨水屏无法分辨深蓝/深红/深绿圆点的问题。 */
    fun shortColorLabel(hex: String): String = when (hex.uppercase()) {
        "#000000", "#111827", "#09090B" -> "黑"
        "#1E3A8A", "#2563EB", "#60A5FA" -> "蓝"
        "#DC2626", "#EF4444" -> "红"
        "#047857", "#16A34A", "#4ADE80" -> "绿"
        "#4B5563", "#6B7280" -> "灰"
        "#6D28D9", "#7C3AED" -> "紫"
        "#EA580C", "#FB923C" -> "橙"
        "#78350F" -> "棕"
        "#F9FAFB", "#FFFFFF" -> "白"
        "#FACC15" -> "黄"
        "#F472B6" -> "粉"
        else -> "色"
    }

    /** 文字胶囊。选中为黑底白字，未选中为白底黑框。 */
    fun text(context: Context, label: CharSequence, selected: Boolean, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            stateListAnimator = null
            minWidth = 0
            minimumWidth = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(context, 32f)
            ).apply {
                marginEnd = dp(context, 6f)
                bottomMargin = dp(context, 4f)
            }
            setPadding(dp(context, 10f), 0, dp(context, 10f), 0)
            if (selected) {
                setBackgroundResource(R.drawable.bg_chip_selected)
                setTextColor(context.getColor(R.color.white))
                setTypeface(typeface, Typeface.BOLD)
            } else {
                setBackgroundResource(R.drawable.bg_chip_unselected)
                setTextColor(context.getColor(R.color.text_primary))
            }
            setOnClickListener { onClick() }
        }

    /** 带彩色圆点前缀的颜色胶囊。 */
    fun color(context: Context, hex: String, name: String, selected: Boolean, onClick: () -> Unit): Button {
        val prefix = if (selected) "✓ " else "● "
        val span = SpannableString("$prefix$name")
        if (!selected) {
            span.setSpan(ForegroundColorSpan(StrokePainter.parseColor(hex)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text(context, span, selected, onClick)
    }

    /**
     * 工具栏上的高对比墨水屏色块：
     * 内嵌单字中文标识（黑/蓝/红/绿/黄等），选中时呈现外圈纯黑粗环 + 内圈白边，
     * 在 16 级灰阶黑白墨水屏上一眼即可分辨色相与激活状态。
     */
    fun swatch(context: Context, hex: String, selected: Boolean, onClick: () -> Unit): View =
        TextView(context).apply {
            val size = dp(context, 26f)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = dp(context, 3f)
                marginEnd = dp(context, 3f)
            }
            val parsed = StrokePainter.parseColor(hex)
            val luminance = (0.299 * Color.red(parsed) + 0.587 * Color.green(parsed) + 0.114 * Color.blue(parsed)) / 255.0
            val label = shortColorLabel(hex)
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (luminance > 0.62) Color.parseColor("#09090B") else Color.WHITE)

            if (selected) {
                val outer = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                    setStroke(dp(context, 2.5f), context.getColor(R.color.border_strong))
                }
                val inner = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(parsed)
                }
                val inset = dp(context, 3.5f)
                background = LayerDrawable(arrayOf(outer, inner)).apply {
                    setLayerInset(1, inset, inset, inset, inset)
                }
            } else {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(parsed)
                    setStroke(dp(context, 1.2f), context.getColor(R.color.border_card))
                }
            }
            contentDescription = "墨水颜色：$label ($hex)"
            setOnClickListener { onClick() }
        }
}
