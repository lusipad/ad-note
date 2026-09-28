package com.adnote.model

import kotlinx.serialization.Serializable

/** 编辑器当前工具。 */
@Serializable
enum class Tool { PEN, HIGHLIGHTER, ERASER, LASSO, SHAPE, TEXT }

/** 橡皮擦模式。 */
@Serializable
enum class EraserMode(val displayName: String) {
    /** 只擦掉橡皮经过的那一段笔迹。 */
    PARTIAL("局部擦除"),

    /** 碰到的笔画整条删除。 */
    STROKE("整笔擦除"),
}

/** 橡皮大小：页面像素半径。工具栏给出几档预设，设置里可以连续调节。 */
object EraserSizes {
    const val MIN = 4f
    const val MAX = 80f
    const val DEFAULT = 18f

    /** 工具栏上的预设档位（半径）。 */
    val PRESETS = listOf(6f, 12f, 18f, 30f, 48f)

    fun clamp(radius: Float): Float = radius.coerceIn(MIN, MAX)
}

/** 常用画笔快捷预设槽位 */
@Serializable
data class PenPreset(
    val tool: Tool = Tool.PEN,
    val penType: PenType = PenType.FOUNTAIN,
    val color: String = PenPresets.BLACK.hex,
    val width: Float = PenPresets.WIDTH_FINE,
    val name: String = "1",
)

/** 工具栏停靠位置模式（避手设计） */
@Serializable
enum class DockPosition(val displayName: String) {
    TOP("顶部停靠"),
    LEFT("左侧避手"),
    RIGHT("右侧避手"),
}

/**
 * 编辑器工具栏的全部状态。每种工具独立记住自己的颜色与粗细，
 * 以 JSON 形式持久化，重新打开笔记时恢复上次用过的笔。
 */
@Serializable
data class ToolState(
    val tool: Tool = Tool.PEN,
    val penType: PenType = PenType.FOUNTAIN,
    val penColor: String = PenPresets.BLACK.hex,
    val penWidth: Float = PenPresets.WIDTH_MEDIUM,
    val highlighterColor: String = PenPresets.HIGHLIGHTERS.first().hex,
    val highlighterWidth: Float = PenPresets.HIGHLIGHTER_WIDTH,
    val eraserMode: EraserMode = EraserMode.PARTIAL,
    /** 橡皮半径（页面像素）。 */
    val eraserRadius: Float = EraserSizes.DEFAULT,
    /** 文字工具的字号（页面像素）与颜色。 */
    val textSize: Float = 40f,
    val textColor: String = PenPresets.BLACK.hex,
    /** 笔工具的最近使用颜色，工具栏上的快捷色板。 */
    val recentPenColors: List<String> = listOf(
        PenPresets.BLACK.hex, PenPresets.BLUE.hex, PenPresets.RED.hex, PenPresets.GREEN.hex,
    ),
    /** 快捷画笔预设槽位（3个预设位，支持一键切换与长按保存）。 */
    val presets: List<PenPreset> = listOf(
        PenPreset(tool = Tool.PEN, penType = PenType.FOUNTAIN, color = PenPresets.BLACK.hex, width = PenPresets.WIDTH_FINE, name = "1"),
        PenPreset(tool = Tool.PEN, penType = PenType.BALLPOINT, color = PenPresets.RED.hex, width = PenPresets.WIDTH_MEDIUM, name = "2"),
        PenPreset(tool = Tool.HIGHLIGHTER, penType = PenType.HIGHLIGHTER, color = PenPresets.HIGHLIGHTERS.first().hex, width = PenPresets.HIGHLIGHTER_WIDTH, name = "3"),
    ),
    /** 工具栏停靠位置（顶部 / 左侧避手 / 右侧避手）。 */
    val dockPosition: DockPosition = DockPosition.TOP,
) {
    /** 匹配当前画笔设置对应的预设索引（若匹配则点亮槽位）。 */
    fun activePresetIndex(): Int? {
        val currentTool = tool
        return presets.indexOfFirst { p ->
            if (p.tool != currentTool) return@indexOfFirst false
            if (currentTool == Tool.HIGHLIGHTER) {
                p.color.equals(highlighterColor, ignoreCase = true) &&
                    kotlin.math.abs(p.width - highlighterWidth) < 0.5f
            } else {
                p.penType == penType &&
                    p.color.equals(penColor, ignoreCase = true) &&
                    kotlin.math.abs(p.width - penWidth) < 0.5f
            }
        }.takeIf { it >= 0 }
    }

    /** 激活某个槽位的画笔设置。 */
    fun applyPreset(preset: PenPreset): ToolState {
        return if (preset.tool == Tool.HIGHLIGHTER) {
            copy(
                tool = Tool.HIGHLIGHTER,
                highlighterColor = preset.color,
                highlighterWidth = preset.width,
            )
        } else {
            copy(
                tool = Tool.PEN,
                penType = preset.penType,
                penColor = preset.color,
                penWidth = preset.width,
            )
        }
    }

    /** 将当前画笔参数存储到指定槽位。 */
    fun savePreset(index: Int): ToolState {
        if (index !in presets.indices) return this
        val current = if (tool == Tool.HIGHLIGHTER) {
            PenPreset(
                tool = Tool.HIGHLIGHTER,
                penType = PenType.HIGHLIGHTER,
                color = highlighterColor,
                width = highlighterWidth,
                name = "${index + 1}"
            )
        } else {
            PenPreset(
                tool = Tool.PEN,
                penType = penType,
                color = penColor,
                width = penWidth,
                name = "${index + 1}"
            )
        }
        val list = presets.toMutableList()
        list[index] = current
        return copy(presets = list)
    }
    /** 当前书写工具实际使用的笔型。 */
    val activePen: PenType get() = if (tool == Tool.HIGHLIGHTER) PenType.HIGHLIGHTER else penType
    val activeColor: String get() = if (tool == Tool.HIGHLIGHTER) highlighterColor else penColor
    val activeWidth: Float get() = if (tool == Tool.HIGHLIGHTER) highlighterWidth else penWidth

    /** 按当前工具生成一条笔画。 */
    fun newStroke(points: List<InkPoint>): Stroke =
        Stroke(points = points, width = activeWidth, color = activeColor, pen = activePen)

    /** 切换笔颜色，并把它放到快捷色板最前面（最多保留 [MAX_RECENT] 个）。 */
    fun withPenColor(hex: String): ToolState {
        val recent = (listOf(hex) + recentPenColors.filterNot { it.equals(hex, ignoreCase = true) })
            .take(MAX_RECENT)
        return copy(penColor = hex, recentPenColors = recent)
    }

    /**
     * 纸张深浅切换时，自动把墨黑/粉笔白互换，避免在暗色纸上书写不可见。
     */
    fun adaptToPaper(isDarkPaper: Boolean): ToolState = when {
        isDarkPaper && penColor.equals(PenPresets.BLACK.hex, ignoreCase = true) ->
            copy(penColor = PenPresets.WHITE.hex)
        !isDarkPaper && penColor.equals(PenPresets.WHITE.hex, ignoreCase = true) ->
            copy(penColor = PenPresets.BLACK.hex)
        else -> this
    }

    companion object {
        const val MAX_RECENT = 5

        fun fromJson(json: String?): ToolState =
            json?.let { runCatching { NoteJson.decodeFromString(serializer(), it) }.getOrNull() } ?: ToolState()

        fun toJson(state: ToolState): String = NoteJson.encodeToString(serializer(), state)
    }
}

/** 笔身按键按下时书写的效果（仅标准触控通道；文石固件的侧键固定为橡皮）。 */
@Serializable
enum class StylusButtonAction(val displayName: String) {
    ERASER("橡皮擦"),
    LASSO("套索选择"),
    HIGHLIGHTER("荧光笔"),
    NONE("不处理（照常书写）"),
}
