package com.adnote.storage

import com.adnote.model.LegacyCoords
import com.adnote.model.Note
import com.adnote.model.NoteJson
import com.adnote.model.Page
import kotlinx.serialization.Serializable
import java.io.File
import java.util.logging.Logger

/** 回收站里的笔记。 */
data class TrashedNote(val note: Note, val deletedAt: Long)

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
    private val trashDir = File(root, "trash").apply { mkdirs() }
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
            coordVersion = LegacyCoords.CURRENT,
        )
        save(note)
        return note
    }

    fun getNoteDir(id: String): File = File(notesDir, id)

    /** 笔记附件（图片、背景、录音）的本地文件；[relPath] 相对笔记目录。 */
    fun assetFile(noteId: String, relPath: String): File = File(getNoteDir(noteId), relPath)

    /** 读取附件字节，文件不存在返回 null。用于 SVG 内嵌图片。 */
    fun readAsset(noteId: String, relPath: String): ByteArray? =
        assetFile(noteId, relPath).takeIf { it.isFile }?.readBytes()

    /** 为新附件分配路径，如 "images/ab12cd34.jpg"。 */
    fun newAssetPath(dir: String, ext: String): String = "$dir/${com.adnote.model.newId()}.$ext"

    /** 把附件从一个笔记复制到另一个笔记，返回新路径（同一笔记直接返回原路径）。 */
    fun copyAsset(fromNoteId: String, relPath: String, toNoteId: String): String {
        if (fromNoteId == toNoteId) return relPath
        val src = assetFile(fromNoteId, relPath)
        val ext = relPath.substringAfterLast('.', "bin")
        val dir = relPath.substringBeforeLast('/', "images")
        val newPath = newAssetPath(dir, ext)
        if (src.isFile) src.copyTo(assetFile(toNoteId, newPath).apply { parentFile?.mkdirs() }, overwrite = true)
        return newPath
    }

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
            pdfPath = "document.pdf",
            coordVersion = LegacyCoords.CURRENT,
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

    /**
     * 移入回收站：本地文件整体搬到 trash/，远端按删除处理（写墓碑）。
     * 恢复时会重新上传，因此远端被删也没关系。
     */
    fun moveToTrash(note: Note, remoteInkDir: String?, now: Long = System.currentTimeMillis()) {
        if (note.sync.lastSyncedAt > 0) {
            val t = Tombstone(note.id, note.sync.remoteMdPath, remoteInkDir)
            atomicWrite(File(tombDir, "${note.id}.json"), NoteJson.encodeToString(Tombstone.serializer(), t))
        }
        val src = File(notesDir, note.id)
        val dst = File(trashDir, note.id)
        dst.deleteRecursively()
        if (!src.renameTo(dst)) {
            src.copyRecursively(dst, overwrite = true)
            src.deleteRecursively()
        }
        File(dst, DELETED_MARK).writeText(now.toString())
    }

    fun listTrash(): List<TrashedNote> =
        trashDir.listFiles().orEmpty().mapNotNull { dir ->
            val f = File(dir, "note.json")
            if (!f.exists()) return@mapNotNull null
            val note = runCatching { NoteJson.decodeFromString(Note.serializer(), f.readText()) }.getOrNull()
                ?: return@mapNotNull null
            val at = File(dir, DELETED_MARK).takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: dir.lastModified()
            TrashedNote(note, at)
        }.sortedByDescending { it.deletedAt }

    /** 从回收站恢复：清掉尚未执行的远端删除，并标记为需要重新同步。 */
    fun restore(id: String, now: Long = System.currentTimeMillis()): Note? {
        val src = File(trashDir, id)
        if (!src.exists()) return null
        File(src, DELETED_MARK).delete()
        val dst = File(notesDir, id)
        if (!src.renameTo(dst)) {
            src.copyRecursively(dst, overwrite = true)
            src.deleteRecursively()
        }
        clearTombstone(id)
        val note = load(id) ?: return null
        val restored = note.copy(updatedAt = now, sync = com.adnote.model.SyncState())
        save(restored)
        return restored
    }

    /** 永久删除回收站中的笔记。 */
    fun purge(id: String) {
        File(trashDir, id).deleteRecursively()
    }

    /** 清理在回收站中超过 [days] 天的笔记。 */
    fun purgeExpired(now: Long = System.currentTimeMillis(), days: Int = 30): Int {
        val expired = listTrash().filter { now - it.deletedAt > days * 24L * 3600_000L }
        expired.forEach { purge(it.note.id) }
        return expired.size
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

    companion object {
        private const val DELETED_MARK = ".deleted_at"
    }

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
