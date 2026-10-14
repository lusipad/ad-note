package com.adnote.storage

import com.adnote.model.Note
import kotlinx.serialization.Serializable

/** 商业级书架多维排序方式。 */
enum class NoteSortOrder(val displayName: String) {
    UPDATED_DESC("按最近修改"),
    CREATED_DESC("按创建时间"),
    TITLE_ASC("按标题名称 (A–Z)"),
    PAGES_DESC("按页数从多到少"),
}

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
    val lastPageIndex: Int = 0,
    val createdAt: Long = updatedAt,
    val isStarred: Boolean = false,
    val snippet: String = "",
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
            lastPageIndex = note.lastPageIndex,
            createdAt = note.createdAt,
            isStarred = note.isStarred,
            snippet = note.snippetPreview(),
        )

        /** 按关键字、标签、文件夹、星标、PDF、待同步状态筛选，并支持多维排序（星标笔记优先置顶）。 */
        fun filter(
            items: List<NoteSummary>,
            query: String,
            tag: String? = null,
            folder: String? = null,
            onlyStarred: Boolean = false,
            onlyPdf: Boolean = false,
            onlyUnsynced: Boolean = false,
            sortOrder: NoteSortOrder? = null,
        ): List<NoteSummary> {
            val q = query.trim().lowercase()
            val matched = items.filter { n ->
                (!onlyStarred || n.isStarred) &&
                    (!onlyPdf || n.isPdf) &&
                    (!onlyUnsynced || n.isDirty) &&
                    (tag == null || tag in n.tags) &&
                    (folder == null || n.folder == folder || n.folder.startsWith("$folder/")) &&
                    (q.isEmpty() || (if (n.isLocked) n.title else n.searchText).lowercase().contains(q))
            }
            if (sortOrder == null) return matched
            val baseComparator: Comparator<NoteSummary> = when (sortOrder) {
                NoteSortOrder.UPDATED_DESC -> compareByDescending { it.updatedAt }
                NoteSortOrder.CREATED_DESC -> compareByDescending { it.createdAt }
                NoteSortOrder.TITLE_ASC -> compareBy<NoteSummary> { it.title.lowercase() }.thenByDescending { it.updatedAt }
                NoteSortOrder.PAGES_DESC -> compareByDescending<NoteSummary> { it.pageCount }.thenByDescending { it.updatedAt }
            }
            // 星标置顶笔记始终排在前面
            val pinnedComparator = compareByDescending<NoteSummary> { it.isStarred }.then(baseComparator)
            return matched.sortedWith(pinnedComparator)
        }

        fun tagsOf(items: List<NoteSummary>): List<String> = items.flatMap { it.tags }.distinct().sorted()

        fun foldersOf(items: List<NoteSummary>): List<String> =
            (items.map { it.folder } + Note.DEFAULT_FOLDER).distinct().sorted()
    }
}
