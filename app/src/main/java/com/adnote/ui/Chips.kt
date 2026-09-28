package com.adnote.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import com.adnote.R

/** 对话框与工具栏里反复用到的胶囊按钮、色块。 */
object Chips {

    private fun dp(context: Context, v: Float): Int = (v * context.resources.displayMetrics.density).toInt()

    /** 文字胶囊。选中为黑底白字。 */
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
            ).apply { marginEnd = dp(context, 8f) }
            setPadding(dp(context, 12f), 0, dp(context, 12f), 0)
            if (selected) {
                setBackgroundResource(R.drawable.bg_chip_selected)
                setTextColor(context.getColor(R.color.white))
            } else {
                setBackgroundResource(R.drawable.bg_chip_unselected)
                setTextColor(context.getColor(R.color.text_primary))
            }
            setOnClickListener { onClick() }
        }

    /** 带彩色圆点前缀的颜色胶囊。 */
    fun color(context: Context, hex: String, name: String, selected: Boolean, onClick: () -> Unit): Button {
        val span = SpannableString("●  $name")
        span.setSpan(ForegroundColorSpan(StrokePainter.parseColor(hex)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return text(context, span, selected, onClick)
    }

    /** 工具栏上的圆形色块。选中时加粗黑框。 */
    fun swatch(context: Context, hex: String, selected: Boolean, onClick: () -> Unit): View =
        View(context).apply {
            val size = dp(context, 24f)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = dp(context, 4f)
                marginEnd = dp(context, 4f)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(StrokePainter.parseColor(hex))
                if (selected) {
                    setStroke(dp(context, 3f), context.getColor(R.color.border_strong))
                } else {
                    setStroke(dp(context, 1f), Color.parseColor("#9CA3AF"))
                }
            }
            contentDescription = hex
            setOnClickListener { onClick() }
        }
}
