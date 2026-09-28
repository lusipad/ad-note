package com.adnote.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 单个采样点。坐标为页面像素坐标，pressure 归一化到 0..1。
 * tilt 为笔身倾角（弧度，0 表示垂直），仅支持倾角的手写笔会写入，默认值不落盘以免 JSON 膨胀。
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class InkPoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 0.5f,
    val t: Long = 0L,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val tilt: Float = 0f,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Stroke(
    val id: String = newId(),
    val points: List<InkPoint>,
    /** 基准笔宽（像素），实际宽度随压感在 [0.4, 1.2] 倍之间变化。 */
    val width: Float = 3f,
    /** 笔迹颜色 Hex，默认墨黑 #000000。 */
    val color: String = "#000000",
    /** 笔型（钢笔、铅笔、荧光笔等）。 */
    val pen: PenType = PenType.FOUNTAIN,
    /** 所属图层 id。 */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val layer: Int = 0,
)

@Serializable
data class Page(
    val id: String = newId(),
    val width: Int,
    val height: Int,
    val strokes: List<Stroke> = emptyList(),
    val recognizedText: String? = null,
    /** 笔记本底质模板：空白、横线、方格、点阵、康奈尔。 */
    val template: PageTemplate = PageTemplate.BLANK,
    /** 纸张底色 Hex，默认纯白 #FFFFFF。 */
    val backgroundColor: String = "#FFFFFF",
    /** 文字框。 */
    val texts: List<TextBox> = emptyList(),
    /** 插入的图片。 */
    val images: List<ImageItem> = emptyList(),
    /** 图层，列表顺序即绘制顺序（后者在上）。 */
    val layers: List<Layer> = listOf(Layer.DEFAULT),
    /** 自定义背景图（相对笔记目录的路径），绘制在纸色之上、底纹之下。 */
    val backgroundImage: String? = null,
    /** 书签标题；非空时出现在目录里。 */
    val bookmark: String? = null,
    /** 上次自动识别时的笔迹指纹，笔迹没变就不重复识别。 */
    val recognizedHash: Int? = null,
) {
    /** 可见图层 id。 */
    fun visibleLayerIds(): Set<Int> = layers.filter { it.visible }.map { it.id }.toSet()

    /** 图层在绘制顺序中的位置；未知图层排在最前。 */
    fun layerOrder(layerId: Int): Int = layers.indexOfFirst { it.id == layerId }

    /** 当前笔迹的指纹（笔画 id 集合），用于判断是否需要重新识别。 */
    fun inkHash(): Int = strokes.filter { it.pen != PenType.HIGHLIGHTER }.map { it.id }.hashCode()
}

@Serializable
data class SyncState(
    val lastSyncedAt: Long = 0L,
    /** 上次同步时 md 文件的远端路径（相对远端根目录），用于检测改名。 */
    val remoteMdPath: String? = null,
    /** 上次同步后远端 md 的 ETag，用于判断是否在别处被修改过。 */
    val remoteMdEtag: String? = null,
    /** 上次同步上传的页面 SVG 数量，删页后用于清理远端多余的 page-NNN.svg。 */
    val remotePageCount: Int = 0,
    /** 已上传过的录音 id。 */
    val uploadedRecordings: List<String> = emptyList(),
)

@Serializable
data class Note(
    val id: String = newId(),
    val title: String,
    /** 以 "/" 分隔的文件夹路径，如 "收件箱" 或 "工作/周会"。 */
    val folder: String = DEFAULT_FOLDER,
    val tags: List<String> = emptyList(),
    val pages: List<Page>,
    val createdAt: Long,
    val updatedAt: Long,
    val sync: SyncState = SyncState(),
    /** 关联的 PDF 文件相对路径（相对笔记目录，如 "document.pdf"）。若为 null 则为普通手写笔记本。 */
    val pdfPath: String? = null,
    /** 录音。 */
    val recordings: List<Recording> = emptyList(),
    /** 打开笔记所需 PIN 的加盐哈希；为空表示未上锁。只是访问锁，不加密文件内容。 */
    val lockHash: String? = null,
    /** 封面色 Hex，显示在笔记列表卡片上。 */
    val coverColor: String? = null,
    /**
     * 坐标版本：0 = 旧版（笔迹按当时的画布视图坐标保存），1 = 页面坐标。
     * 旧笔记第一次在编辑器打开时由 [LegacyCoords] 迁移。
     */
    val coordVersion: Int = 0,
) {
    val isLocked: Boolean get() = !lockHash.isNullOrEmpty()

    val isDirty: Boolean get() = updatedAt > sync.lastSyncedAt
    val isPdf: Boolean get() = !pdfPath.isNullOrBlank()

    /** 用于本地搜索的全文：标题 + 标签 + 识别文字。 */
    fun searchableText(): String = buildString {
        append(title).append('\n')
        tags.forEach { append('#').append(it).append(' ') }
        pages.forEach { p ->
            p.recognizedText?.let { append('\n').append(it) }
            p.texts.forEach { append('\n').append(it.text) }
            p.bookmark?.let { append('\n').append(it) }
        }
    }

    companion object {
        const val DEFAULT_FOLDER = "收件箱"
    }
}

fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

val NoteJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
