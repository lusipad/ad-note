package com.adnote.model

import kotlinx.serialization.Serializable

@Serializable
enum class PageTemplate(
    val displayName: String,
    val category: String = "常规"
) {
    BLANK("空白", "常规"),
    RULED("横线·中", "横线"),
    RULED_WIDE("横线·宽", "横线"),
    RULED_NARROW("横线·窄", "横线"),
    GRID("方格·中", "方格"),
    GRID_LARGE("方格·大", "方格"),
    GRID_SMALL("方格·密", "方格"),
    DOT("点阵·中", "点阵"),
    DOT_DENSE("点阵·密", "点阵"),
    TIANZI("田字格", "练字"),
    MIZI("米字格", "练字"),
    PINYIN("拼音四线格", "专业"),
    MUSIC("音乐五线谱", "专业"),
    CORNELL("康奈尔", "常规"),
    TODO("待办清单", "常规"),
    ENGINEERING("坐标纸", "方格"),
    ISOMETRIC("等距点阵", "点阵"),
    MANUSCRIPT("作文稿纸", "练字");

    companion object {
        val CATEGORIES = listOf("全部", "常规", "横线", "方格", "点阵", "练字", "专业")

        fun fromName(name: String?): PageTemplate {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: BLANK
        }

        fun inCategory(category: String): List<PageTemplate> =
            if (category == "全部") entries else entries.filter { it.category == category }
    }
}

object PaperPresets {
    val WHITE = PaperTone("#FFFFFF", "纯白", isDark = false)
    val CREAM = PaperTone("#FBF8F1", "米黄", isDark = false)
    val KRAFT = PaperTone("#F0EAE1", "牛皮", isDark = false)
    val MINT = PaperTone("#EEF5EC", "豆沙绿", isDark = false)
    val SKY = PaperTone("#EEF3F9", "淡蓝", isDark = false)
    val DARK = PaperTone("#1E1E20", "暗黑", isDark = true)
    val BLACKBOARD = PaperTone("#1F3A30", "黑板", isDark = true)

    val ALL = listOf(WHITE, CREAM, KRAFT, MINT, SKY, DARK, BLACKBOARD)

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
    val PURPLE = PenStyle("#6D28D9", "葡萄紫")
    val ORANGE = PenStyle("#EA580C", "活力橙")
    val BROWN = PenStyle("#78350F", "咖啡棕")
    val WHITE = PenStyle("#F9FAFB", "粉笔白")

    val ALL = listOf(BLACK, BLUE, RED, GREEN, GREY, PURPLE, ORANGE, BROWN, WHITE)

    /** 荧光笔颜色（半透明叠加在文字上方）。 */
    val HIGHLIGHTERS = listOf(
        PenStyle("#FACC15", "柠檬黄"),
        PenStyle("#4ADE80", "薄荷绿"),
        PenStyle("#F472B6", "樱花粉"),
        PenStyle("#60A5FA", "天空蓝"),
        PenStyle("#FB923C", "橘橙"),
    )

    const val WIDTH_FINE = 2.0f
    const val WIDTH_MEDIUM = 3.5f
    const val WIDTH_BOLD = 6.0f

    const val WIDTH_MIN = 1.0f
    const val WIDTH_MAX = 24.0f

    const val HIGHLIGHTER_WIDTH = 18f

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
