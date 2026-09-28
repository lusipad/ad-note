package com.adnote.model

import kotlinx.serialization.Serializable

/** 编辑器当前工具。 */
@Serializable
enum class Tool { PEN, HIGHLIGHTER, ERASER, LASSO }

/** 橡皮擦模式。 */
@Serializable
enum class EraserMode(val displayName: String) {
    /** 只擦掉橡皮经过的那一段笔迹。 */
    PARTIAL("局部擦除"),

    /** 碰到的笔画整条删除。 */
    STROKE("整笔擦除"),
}

/** 橡皮尺寸档位，radius 为页面像素半径。 */
@Serializable
enum class EraserSize(val displayName: String, val radius: Float) {
    SMALL("小", 8f),
    MEDIUM("中", 18f),
    LARGE("大", 36f),
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
    val eraserSize: EraserSize = EraserSize.MEDIUM,
    /** 笔工具的最近使用颜色，工具栏上的快捷色板。 */
    val recentPenColors: List<String> = listOf(
        PenPresets.BLACK.hex, PenPresets.BLUE.hex, PenPresets.RED.hex, PenPresets.GREEN.hex,
    ),
) {
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
