package com.adnote.storage

import com.adnote.model.Note
import com.adnote.model.NoteJson
import com.adnote.model.Page
import kotlinx.serialization.Serializable
import java.io.File
import java.util.logging.Logger

/** 删除记录：本地删除的笔记，等待下次同步时删掉远端文件。 */
@Serializable
data class Tombstone(val noteId: String, val remoteMdPath: String?, val remoteInkDir: String?)

/**
 * 本地笔记存储。每个笔记一个 JSON 文件：root/notes/<id>/note.json。
 * 只依赖 java.io，可在 JVM 单元测试中直接使用。
 */
class NoteRepository(private val root: File) {

    private val notesDir = File(root, "notes").apply { mkdirs() }
    private val tombDir = File(root, "tombstones").apply { mkdirs() }
    private val log = Logger.getLogger("NoteRepository")

    fun list(): List<Note> =
        notesDir.listFiles().orEmpty()
            .mapNotNull { dir -> load(dir.name) }
            .sortedByDescending { it.updatedAt }

    fun load(id: String): Note? {
        val f = File(notesDir, "$id/note.json")
        if (!f.exists()) return null
        return try {
            NoteJson.decodeFromString(Note.serializer(), f.readText())
        } catch (e: Exception) {
            // 单个笔记损坏不应拖垮整个列表
            log.warning("跳过损坏的笔记 $id: ${e.message}")
            null
        }
    }

    fun save(note: Note) {
        val dir = File(notesDir, note.id).apply { mkdirs() }
        atomicWrite(File(dir, "note.json"), NoteJson.encodeToString(Note.serializer(), note))
    }

    fun create(
        title: String,
        folder: String,
        pageWidth: Int,
        pageHeight: Int,
        template: com.adnote.model.PageTemplate = com.adnote.model.PageTemplate.BLANK,
        backgroundColor: String = "#FFFFFF",
        now: Long = System.currentTimeMillis()
    ): Note {
        val note = Note(
            title = title,
            folder = folder,
            pages = listOf(Page(width = pageWidth, height = pageHeight, template = template, backgroundColor = backgroundColor)),
            createdAt = now,
            updatedAt = now,
        )
        save(note)
        return note
    }

    fun getNoteDir(id: String): File = File(notesDir, id)

    fun getPdfFile(note: Note): File? {
        val rel = note.pdfPath ?: return null
        return File(getNoteDir(note.id), rel)
    }

    fun createPdfNote(
        title: String,
        folder: String,
        pdfSource: java.io.InputStream,
        pages: List<Page>,
        now: Long = System.currentTimeMillis()
    ): Note {
        val note = Note(
            title = title,
            folder = folder,
            pages = pages,
            createdAt = now,
            updatedAt = now,
            pdfPath = "document.pdf"
        )
        val dir = getNoteDir(note.id).apply { mkdirs() }
        val targetPdf = File(dir, "document.pdf")
        targetPdf.outputStream().use { out ->
            pdfSource.copyTo(out)
        }
        save(note)
        return note
    }

    fun delete(note: Note, remoteInkDir: String?) {
        if (note.sync.lastSyncedAt > 0) {
            val t = Tombstone(note.id, note.sync.remoteMdPath, remoteInkDir)
            atomicWrite(File(tombDir, "${note.id}.json"), NoteJson.encodeToString(Tombstone.serializer(), t))
        }
        File(notesDir, note.id).deleteRecursively()
    }

    fun tombstones(): List<Tombstone> =
        tombDir.listFiles().orEmpty().mapNotNull {
            runCatching { NoteJson.decodeFromString(Tombstone.serializer(), it.readText()) }.getOrNull()
        }

    fun clearTombstone(noteId: String) {
        File(tombDir, "$noteId.json").delete()
    }

    fun search(query: String, tag: String? = null, folder: String? = null): List<Note> {
        val q = query.trim().lowercase()
        return list().filter { n ->
            (tag == null || tag in n.tags) &&
                (folder == null || n.folder == folder || n.folder.startsWith("$folder/")) &&
                (q.isEmpty() || n.searchableText().lowercase().contains(q))
        }
    }

    fun allTags(): List<String> = list().flatMap { it.tags }.distinct().sorted()

    fun allFolders(): List<String> =
        (list().map { it.folder } + Note.DEFAULT_FOLDER).distinct().sorted()

    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            // Windows 上 rename 不能覆盖已存在文件（单元测试环境）
            target.delete()
            check(tmp.renameTo(target)) { "无法写入 ${target.path}" }
        }
    }
}
