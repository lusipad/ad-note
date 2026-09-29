package com.adnote.storage

import com.adnote.model.LegacyCoords
import com.adnote.model.Note
import com.adnote.model.NoteJson
import com.adnote.model.Page
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    /** 写文件的锁：编辑器的后台保存与同步线程可能同时保存同一篇笔记。 */
    private val writeLock = Any()

    /**
     * 后台保存队列。整本笔记序列化成 JSON 可能要几百毫秒，放在界面线程会卡住书写。
     * 还没写到磁盘的最新版本放在 [pending] 里，[load]、[list] 优先返回它，读到的永远是最新内容。
     */
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "note-writer").apply { isDaemon = true } }
    private val pending = ConcurrentHashMap<String, Note>()

    /**
     * 本进程里写过的最新同步状态。编辑器打开笔记后内存里拿的是旧的同步状态，
     * 同步在这期间完成的话，编辑器下次保存会把它改回去（导致重复上传、远端标签被当成外部修改）。
     * 保存时同步状态只进不退。
     */
    private val syncStates = ConcurrentHashMap<String, com.adnote.model.SyncState>()

    private fun withLatestSync(note: Note): Note {
        val known = syncStates[note.id] ?: return note
        return if (known.lastSyncedAt > note.sync.lastSyncedAt) note.copy(sync = known) else note
    }

    fun list(): List<Note> =
        notesDir.listFiles().orEmpty()
            .mapNotNull { dir -> load(dir.name) }
            .sortedByDescending { it.updatedAt }

    fun load(id: String): Note? {
        pending[id]?.let { return it }
        val dir = File(notesDir, id)
        val f = File(dir, "note.json")
        val bak = File(dir, "note.json.bak")
        val tmp = File(dir, "note.json.tmp")

        fun tryDecode(file: File): Note? {
            if (!file.exists()) return null
            return try {
                NoteJson.decodeFromString(Note.serializer(), file.readText())
            } catch (e: Exception) {
                log.warning("解析笔记文件 ${file.path} 失败: ${e.message}")
                null
            }
        }

        return tryDecode(f)
            ?: tryDecode(bak)?.also { log.info("从备份 note.json.bak 成功恢复笔记 $id") }
            ?: tryDecode(tmp)?.also { log.info("从临时 note.json.tmp 成功恢复笔记 $id") }
    }

    /** 立即写入磁盘（在调用线程上）。 */
    fun save(note: Note) {
        synchronized(writeLock) {
            val n = withLatestSync(note)
            val dir = File(notesDir, n.id).apply { mkdirs() }
            atomicWrite(File(dir, "note.json"), NoteJson.encodeToString(Note.serializer(), n))
            syncStates[n.id] = n.sync
            writeSummary(dir, NoteSummary.of(n))
        }
    }

    /**
     * 全部笔记的列表信息，按修改时间倒序。优先读每篇笔记旁的 meta.json；
     * 没有或比笔记旧（旧版本写的笔记、别处改过）时解析一次笔记并补写。
     */
    fun summaries(): List<NoteSummary> =
        notesDir.listFiles().orEmpty().mapNotNull { dir ->
            pending[dir.name]?.let { return@mapNotNull NoteSummary.of(it) }
            val noteFile = File(dir, "note.json")
            if (!noteFile.exists()) return@mapNotNull null
            val metaFile = File(dir, META_FILE)
            if (metaFile.exists() && metaFile.lastModified() >= noteFile.lastModified()) {
                runCatching { NoteJson.decodeFromString(NoteSummary.serializer(), metaFile.readText()) }
                    .getOrNull()?.takeIf { it.id == dir.name }?.let { return@mapNotNull it }
            }
            val note = load(dir.name) ?: return@mapNotNull null
            NoteSummary.of(note).also { runCatching { synchronized(writeLock) { writeSummary(dir, it) } } }
        }.sortedByDescending { it.updatedAt }

    private fun writeSummary(dir: File, summary: NoteSummary) {
        atomicWrite(File(dir, META_FILE), NoteJson.encodeToString(NoteSummary.serializer(), summary))
    }

    /** 在后台保存。同一笔记排队中的多次保存只写最后一次。 */
    fun saveAsync(note: Note) {
        if (pending.put(note.id, withLatestSync(note)) == null) writer.execute { writePending(note.id) }
    }

    private fun writePending(id: String) {
        val n = pending[id] ?: return
        try {
            save(n)
        } catch (e: Exception) {
            log.warning("保存笔记 $id 失败: ${e.message}")
        } finally {
            // 写的过程中又有新版本进来：再排一次
            if (!pending.remove(id, n)) writer.execute { writePending(id) }
        }
    }

    /** 等后台保存全部写完（离开编辑器、删除笔记前调用）。 */
    fun flush(timeoutMs: Long = 10_000) {
        runCatching { writer.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS) }
        // 排在后面又重新入队的写入，再等一轮
        if (pending.isNotEmpty()) runCatching { writer.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS) }
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
        // 还在排队的保存不能在删除后把笔记重新写回来
        flush()
        pending.remove(note.id)
        syncStates.remove(note.id)
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
        flush()
        pending.remove(note.id)
        syncStates.remove(note.id)
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

    /** 回收站里的笔记数（不解析笔记内容）。 */
    fun trashCount(): Int = trashDir.listFiles().orEmpty().count { File(it, "note.json").exists() }

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
        // 恢复后要整篇重新上传：同步状态清零，不能被之前记下的状态顶回去
        syncStates.remove(id)
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

    /**
     * 登记一次远端清理：改名或移动时 MOVE 失败，旧路径的文件留在远端，
     * 由同步流程当作墓碑删除。键带时间戳，不与笔记本身的墓碑冲突。
     */
    fun addRemoteCleanup(noteId: String, remoteMdPath: String?, remoteInkDir: String?) {
        if (remoteMdPath == null && remoteInkDir == null) return
        val key = "$noteId-cleanup-${System.nanoTime()}"
        atomicWrite(File(tombDir, "$key.json"), NoteJson.encodeToString(Tombstone.serializer(), Tombstone(key, remoteMdPath, remoteInkDir)))
    }

    fun search(query: String, tag: String? = null, folder: String? = null): List<Note> =
        filter(list(), query, tag, folder)

    fun allTags(): List<String> = tagsOf(list())

    fun allFolders(): List<String> = foldersOf(list())

    companion object {
        private const val DELETED_MARK = ".deleted_at"
        private const val META_FILE = "meta.json"

        /** 按关键字、标签、文件夹筛选（在已加载的列表上做，不再读磁盘）。 */
        fun filter(notes: List<Note>, query: String, tag: String? = null, folder: String? = null): List<Note> {
            val q = query.trim().lowercase()
            return notes.filter { n ->
                (tag == null || tag in n.tags) &&
                    (folder == null || n.folder == folder || n.folder.startsWith("$folder/")) &&
                    (q.isEmpty() || n.searchableText().lowercase().contains(q))
            }
        }

        fun tagsOf(notes: List<Note>): List<String> = notes.flatMap { it.tags }.distinct().sorted()

        fun foldersOf(notes: List<Note>): List<String> =
            (notes.map { it.folder } + Note.DEFAULT_FOLDER).distinct().sorted()
    }

    private fun atomicWrite(target: File, content: String) {
        val parent = target.parentFile ?: return
        val tmp = File(parent, target.name + ".tmp")
        val bak = File(parent, target.name + ".bak")
        tmp.writeText(content)
        if (target.exists()) {
            runCatching { target.copyTo(bak, overwrite = true) }
        }
        if (!tmp.renameTo(target)) {
            // Windows 上 rename 不能覆盖已存在文件（单元测试环境）
            target.delete()
            check(tmp.renameTo(target)) { "无法写入 ${target.path}" }
        }
    }
}
