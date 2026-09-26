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
     */
    fun assignMdPaths(notes: List<Note>): Map<String, String> {
        val result = HashMap<String, String>()
        val used = HashSet<String>()
        for (note in notes.sortedBy { it.createdAt }) {
            val base = "${folderPath(note)}/${sanitize(note.title)}"
            var path = "$base.md"
            if (!used.add(path.lowercase())) {
                path = "$base (${note.id.take(4)}).md"
                used += path.lowercase()
            }
            result[note.id] = path
        }
        return result
    }
}
