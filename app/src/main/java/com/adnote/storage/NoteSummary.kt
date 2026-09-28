package com.adnote.storage

import com.adnote.model.Note
import kotlinx.serialization.Serializable

/**
 * 笔记列表需要的信息。主界面只读这份小文件（每篇笔记旁边的 meta.json），
 * 不必为了显示列表把每篇笔记的全部笔迹解析一遍。
 */
@Serializable
data class NoteSummary(
    val id: String,
    val title: String,
    val folder: String,
    val tags: List<String> = emptyList(),
    val updatedAt: Long,
    val pageCount: Int,
    val isPdf: Boolean = false,
    val coverColor: String? = null,
    val lockHash: String? = null,
    val lastSyncedAt: Long = 0L,
    /** 标题、标签、识别文字、文字框、书签，用于搜索。 */
    val searchText: String = "",
) {
    val isLocked: Boolean get() = !lockHash.isNullOrEmpty()
    val isDirty: Boolean get() = updatedAt > lastSyncedAt || lastSyncedAt == 0L

    companion object {
        fun of(note: Note) = NoteSummary(
            id = note.id,
            title = note.title,
            folder = note.folder,
            tags = note.tags,
            updatedAt = note.updatedAt,
            pageCount = note.pages.size,
            isPdf = note.isPdf,
            coverColor = note.coverColor,
            lockHash = note.lockHash,
            lastSyncedAt = note.sync.lastSyncedAt,
            searchText = note.searchableText(),
        )

        /** 按关键字、标签、文件夹筛选。 */
        fun filter(items: List<NoteSummary>, query: String, tag: String? = null, folder: String? = null): List<NoteSummary> {
            val q = query.trim().lowercase()
            return items.filter { n ->
                (tag == null || tag in n.tags) &&
                    (folder == null || n.folder == folder || n.folder.startsWith("$folder/")) &&
                    (q.isEmpty() || n.searchText.lowercase().contains(q))
            }
        }

        fun tagsOf(items: List<NoteSummary>): List<String> = items.flatMap { it.tags }.distinct().sorted()

        fun foldersOf(items: List<NoteSummary>): List<String> =
            (items.map { it.folder } + Note.DEFAULT_FOLDER).distinct().sorted()
    }
}
