package com.adnote.sync

import com.adnote.export.RemotePaths
import com.adnote.model.Note
import com.adnote.model.NoteJson
import com.adnote.model.SyncState
import com.adnote.storage.NoteRepository
import java.io.File

enum class LocalState(val displayName: String) {
    MISSING("本地不存在"),
    OLDER("本地较旧"),
    SAME_OR_NEWER("已是最新"),
}

/** 远端找到的一篇笔记。[folder] 是 md 所在的远端目录（相对根目录，根目录为空串），[inkDir] 是 _ink/<id> 的相对路径。 */
data class RemoteNoteInfo(
    val note: Note,
    val folder: String,
    val inkDir: String,
    val localState: LocalState,
)

data class RestoreResult(val restored: Int, val failed: Int, val firstError: String?)

/**
 * 从 WebDAV 恢复笔记：遍历远端目录树找 `_ink/<id>/ink.json`，下载笔记与附件写回本地。
 * 恢复后的笔记视为「已同步到 updatedAt 那一刻」，不会立即重新上传；ETag 未知，下次同步会重新拉取一次远端 md 合并。
 */
class RestoreEngine(
    private val repository: NoteRepository,
    private val webDavClient: WebDavClient,
) {

    fun scan(maxDepth: Int = 6): List<RemoteNoteInfo> {
        val found = ArrayList<RemoteNoteInfo>()
        walk("", 0, maxDepth, found)
        // 同一篇笔记在远端可能有多份（旧目录残留、手工复制）：只保留 updatedAt 最新的一份
        return found.groupBy { it.note.id }
            .map { (_, copies) -> copies.maxByOrNull { it.note.updatedAt }!! }
            .sortedByDescending { it.note.updatedAt }
    }

    private fun walk(dir: String, depth: Int, maxDepth: Int, out: MutableList<RemoteNoteInfo>) {
        if (depth > maxDepth) return
        val entries = webDavClient.list(dir)
        entries.firstOrNull { it.isDir && it.name == "_ink" }?.let { inkRoot ->
            for (noteDir in webDavClient.list(inkRoot.path).filter { it.isDir }) {
                val json = runCatching { webDavClient.get("${noteDir.path}/ink.json") }.getOrNull() ?: continue
                val note = runCatching { NoteJson.decodeFromString(Note.serializer(), json.body.orEmpty()) }.getOrNull() ?: continue
                // ink.json 来自远端，id 会被当作本地目录名：必须与所在目录同名且不含路径分隔符
                val id = note.id
                if (id.isEmpty() || id != noteDir.name || id.contains('/') || id.contains('\\')|| id.contains("..")) continue
                out += RemoteNoteInfo(note, dir, noteDir.path, localState(note))
            }
        }
        for (e in entries) {
            if (e.isDir && e.name != "_ink") walk(e.path, depth + 1, maxDepth, out)
        }
    }

    private fun localState(remote: Note): LocalState {
        val local = repository.load(remote.id) ?: return LocalState.MISSING
        return if (local.updatedAt < remote.updatedAt) LocalState.OLDER else LocalState.SAME_OR_NEWER
    }

    fun restore(infos: List<RemoteNoteInfo>): RestoreResult {
        var restored = 0
        var failed = 0
        var firstError: String? = null
        for (info in infos) {
            try {
                restoreOne(info)
                restored++
            } catch (e: WebDavAuthException) {
                return RestoreResult(restored, infos.size - restored, e.message)
            } catch (e: Exception) {
                failed++
                if (firstError == null) firstError = "${info.note.title}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        return RestoreResult(restored, failed, firstError)
    }

    private fun restoreOne(info: RemoteNoteInfo) {
        val note = info.note
        val dir = repository.getNoteDir(note.id).apply { mkdirs() }
        val downloaded = ArrayList<String>()
        for (rel in NoteAssets.referenced(note)) {
            // 附件路径同样来自远端：目标必须落在笔记目录内
            val target = File(dir, rel)
            if (!target.canonicalPath.startsWith(dir.canonicalPath + File.separator)) continue
            if (webDavClient.download("${info.inkDir}/$rel", target)) downloaded += rel
        }
        val mdName = "${RemotePaths.sanitize(note.title)}.md"
        val mdPath = if (info.folder.isEmpty()) mdName else "${info.folder}/$mdName"
        val sync = SyncState(
            lastSyncedAt = note.updatedAt,
            remoteMdPath = mdPath,
            remoteMdEtag = null,
            remoteMdHash = null,
            syncedTags = note.tags,
            remotePageCount = note.pages.size,
            uploadedAssets = downloaded,
        )
        repository.importNote(note.copy(sync = sync))
    }
}
