package com.adnote.model

import kotlinx.serialization.Serializable

@Serializable
enum class PageTemplate(val displayName: String) {
    BLANK("空白"),
    RULED("横线"),
    GRID("方格"),
    DOT("点阵"),
    CORNELL("康奈尔");

    companion object {
        fun fromName(name: String?): PageTemplate {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: BLANK
        }
    }
}

object PaperPresets {
    val WHITE = PaperTone("#FFFFFF", "纯白", isDark = false)
    val CREAM = PaperTone("#FBF8F1", "米黄", isDark = false)
    val KRAFT = PaperTone("#F0EAE1", "牛皮", isDark = false)
    val DARK = PaperTone("#1E1E20", "暗黑", isDark = true)

    val ALL = listOf(WHITE, CREAM, KRAFT, DARK)

    fun find(hex: String?): PaperTone {
        return ALL.firstOrNull { it.hex.equals(hex, ignoreCase = true) } ?: WHITE
    }
}

data class PaperTone(
    val hex: String,
    val displayName: String,
    val isDark: Boolean = false
)

object PenPresets {
    val BLACK = PenStyle("#000000", "墨黑")
    val BLUE = PenStyle("#1E3A8A", "商务蓝")
    val RED = PenStyle("#DC2626", "批注红")
    val GREEN = PenStyle("#047857", "森林绿")
    val GREY = PenStyle("#4B5563", "铅笔灰")
    val WHITE = PenStyle("#F9FAFB", "粉笔白")

    val ALL = listOf(BLACK, BLUE, RED, GREEN, GREY, WHITE)

    const val WIDTH_FINE = 2.0f
    const val WIDTH_MEDIUM = 3.5f
    const val WIDTH_BOLD = 6.0f

    val WIDTHS = listOf(
        WidthOption(WIDTH_FINE, "细 (2.0)"),
        WidthOption(WIDTH_MEDIUM, "中 (3.5)"),
        WidthOption(WIDTH_BOLD, "粗 (6.0)")
    )
}

data class PenStyle(
    val hex: String,
    val displayName: String
)

data class WidthOption(
    val width: Float,
    val displayName: String
)
