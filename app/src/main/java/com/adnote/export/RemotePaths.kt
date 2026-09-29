package com.adnote.export

import com.adnote.model.Note

/** 计算笔记在远端（相对远端根目录）的文件路径。 */
object RemotePaths {

    private val ILLEGAL = Regex("""[\\/:*?"<>|#^\[\]\u0000-\u001f]""")

    fun sanitize(name: String, fallback: String = "未命名"): String {
        val s = name.replace(ILLEGAL, "_").trim().trim('.').take(80)
        return s.ifEmpty { fallback }
    }

    fun folderPath(note: Note): String =
        note.folder.split('/').filter { it.isNotBlank() }.joinToString("/") { sanitize(it) }
            .ifEmpty { Note.DEFAULT_FOLDER }

    /** md 所在目录下的笔迹目录（相对 md）。 */
    fun inkDirRelative(note: Note): String = "_ink/${note.id}"

    fun inkDir(note: Note): String = "${folderPath(note)}/${inkDirRelative(note)}"

    /**
     * 为所有笔记分配 md 路径。同一文件夹下标题重复时，给后者追加 id 前缀，保证路径唯一且稳定。
     * 远端已经在用某条路径的笔记优先保住它（文件夹合并时搬进来的老笔记不能把原住笔记的 md 顶掉），
     * 没有笔记占着的路径按创建时间先来先得。
     */
    fun assignMdPaths(notes: List<Note>): Map<String, String> {
        val result = HashMap<String, String>()
        val used = HashSet<String>()
        val byCreation = notes.sortedBy { it.createdAt }
        val plain = byCreation.associate { it.id to "${folderPath(it)}/${sanitize(it.title)}.md" }
        fun suffixed(note: Note) = plain.getValue(note.id).removeSuffix(".md") + " (${note.id.take(4)}).md"

        // 1. 已同步的笔记先认领自己现在的路径（普通形式或带后缀形式）
        for (note in byCreation) {
            val current = note.sync.remoteMdPath ?: continue
            if (current != plain.getValue(note.id) && current != suffixed(note)) continue
            if (used.add(current.lowercase())) result[note.id] = current
        }
        // 2. 其余笔记按创建时间分配，撞上的加后缀
        for (note in byCreation) {
            if (note.id in result) continue
            var path = plain.getValue(note.id)
            if (!used.add(path.lowercase())) {
                path = suffixed(note)
                used += path.lowercase()
            }
            result[note.id] = path
        }
        return result
    }
}
