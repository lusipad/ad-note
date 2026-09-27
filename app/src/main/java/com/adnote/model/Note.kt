package com.adnote.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/** 单个采样点。坐标为页面像素坐标，pressure 归一化到 0..1。 */
@Serializable
data class InkPoint(val x: Float, val y: Float, val pressure: Float = 0.5f, val t: Long = 0L)

@Serializable
data class Stroke(
    val id: String = newId(),
    val points: List<InkPoint>,
    /** 基准笔宽（像素），实际宽度随压感在 [0.4, 1.2] 倍之间变化。 */
    val width: Float = 3f,
)

@Serializable
data class Page(
    val id: String = newId(),
    val width: Int,
    val height: Int,
    val strokes: List<Stroke> = emptyList(),
    val recognizedText: String? = null,
)

@Serializable
data class SyncState(
    val lastSyncedAt: Long = 0L,
    /** 上次同步时 md 文件的远端路径（相对远端根目录），用于检测改名。 */
    val remoteMdPath: String? = null,
    /** 上次同步后远端 md 的 ETag，用于判断是否在别处被修改过。 */
    val remoteMdEtag: String? = null,
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
) {
    val isDirty: Boolean get() = updatedAt > sync.lastSyncedAt
    val isPdf: Boolean get() = !pdfPath.isNullOrBlank()

    /** 用于本地搜索的全文：标题 + 标签 + 识别文字。 */
    fun searchableText(): String = buildString {
        append(title).append('\n')
        tags.forEach { append('#').append(it).append(' ') }
        pages.forEach { p -> p.recognizedText?.let { append('\n').append(it) } }
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
