package com.adnote.sync

import com.adnote.export.MarkdownComposer
import com.adnote.export.RemotePaths
import com.adnote.export.SvgExporter
import com.adnote.model.Note
import com.adnote.model.NoteJson
import com.adnote.model.SyncState
import com.adnote.storage.NoteRepository

data class SyncResult(
    val total: Int,
    val success: Int,
    val failed: Int,
    val firstError: String? = null,
)

class SyncEngine(
    private val repository: NoteRepository,
    private val webDavClient: WebDavClient,
) {

    /**
     * 执行全量同步（推送为主，合并 Obsidian 标签与用户区）。
     *
     * @param force 是否强制同步所有笔记（即使未标记为 dirty）
     */
    fun sync(force: Boolean = false): SyncResult {
        // 1. 处理已删除笔记的 Tombstone
        for (tombstone in repository.tombstones()) {
            try {
                tombstone.remoteMdPath?.let { webDavClient.delete(it) }
                tombstone.remoteInkDir?.let { webDavClient.delete(it) }
                repository.clearTombstone(tombstone.noteId)
            } catch (e: WebDavAuthException) {
                return SyncResult(total = 0, success = 0, failed = 1, firstError = e.message)
            } catch (_: Exception) {
                // 删除失败留待下次同步继续尝试
            }
        }

        val allNotes = repository.list()
        val mdPaths = RemotePaths.assignMdPaths(allNotes)
        val dirtyNotes = allNotes.filter { force || it.isDirty || it.sync.lastSyncedAt == 0L }

        var successCount = 0
        var failedCount = 0
        var firstError: String? = null

        for (note in dirtyNotes) {
            try {
                syncSingleNote(note, mdPaths[note.id] ?: "${RemotePaths.folderPath(note)}/${RemotePaths.sanitize(note.title)}.md")
                successCount++
            } catch (e: WebDavAuthException) {
                // 认证失败立即整体中止
                return SyncResult(
                    total = dirtyNotes.size,
                    success = successCount,
                    failed = dirtyNotes.size - successCount,
                    firstError = e.message,
                )
            } catch (e: Exception) {
                failedCount++
                if (firstError == null) {
                    firstError = "${note.title}: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }

        return SyncResult(
            total = dirtyNotes.size,
            success = successCount,
            failed = failedCount,
            firstError = firstError,
        )
    }

    private fun syncSingleNote(originalNote: Note, targetMdPath: String) {
        val oldMdPath = originalNote.sync.remoteMdPath
        val oldInkDir = originalNote.sync.remoteMdPath?.let {
            val folder = it.substringBeforeLast('/', "")
            if (folder.isEmpty()) "_ink/${originalNote.id}" else "$folder/_ink/${originalNote.id}"
        }
        val targetInkDir = RemotePaths.inkDir(originalNote)
        val inkDirRelative = RemotePaths.inkDirRelative(originalNote)

        // 1. 标题或文件夹发生变化，尝试 MOVE
        if (oldMdPath != null && oldMdPath != targetMdPath) {
            runCatching {
                webDavClient.move(oldMdPath, targetMdPath)
            }
            if (oldInkDir != null && oldInkDir != targetInkDir) {
                runCatching {
                    webDavClient.move(oldInkDir, targetInkDir)
                }
            }
        }

        // 2. 获取远端已有的 Markdown
        val existingResp = webDavClient.get(targetMdPath)
        var mergedTags = originalNote.tags

        if (existingResp != null) {
            val parsed = MarkdownComposer.parse(existingResp.body.orEmpty())
            // 如果远端 ETag 与上次记录的不同，说明在 Obsidian 端修改过，采用远端 tags
            if (existingResp.etag != null && originalNote.sync.remoteMdEtag != null &&
                existingResp.etag != originalNote.sync.remoteMdEtag
            ) {
                parsed.tags?.let { mergedTags = it }
            }
        }

        val noteWithTags = if (mergedTags != originalNote.tags) {
            originalNote.copy(tags = mergedTags)
        } else originalNote

        // 3. 组合并上传 Markdown
        val mdContent = MarkdownComposer.compose(
            note = noteWithTags,
            inkDir = inkDirRelative,
            existing = existingResp?.body,
        )
        val putMdResp = webDavClient.put(targetMdPath, mdContent, "text/markdown; charset=utf-8")

        // 4. 上传各页 SVG
        for ((index, page) in noteWithTags.pages.withIndex()) {
            val svg = SvgExporter.export(page)
            val fileName = MarkdownComposer.pageFileName(index)
            webDavClient.put("$targetInkDir/$fileName", svg, "image/svg+xml; charset=utf-8")
        }

        // 5. 上传原始 ink.json（供灾备恢复）
        val inkJson = NoteJson.encodeToString(Note.serializer(), noteWithTags)
        webDavClient.put("$targetInkDir/ink.json", inkJson, "application/json; charset=utf-8")

        // 6. 更新本地同步状态
        val updatedNote = noteWithTags.copy(
            sync = SyncState(
                lastSyncedAt = System.currentTimeMillis(),
                remoteMdPath = targetMdPath,
                remoteMdEtag = putMdResp.etag ?: existingResp?.etag,
            ),
        )
        repository.save(updatedNote)
    }
}
