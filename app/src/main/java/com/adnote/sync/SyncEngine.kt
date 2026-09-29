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

        // 2. 获取远端已有的 Markdown，判断 Obsidian 端是否改过，改过则三方合并标签、采用远端正文
        val existingResp = webDavClient.get(targetMdPath)
        var mergedTags = originalNote.tags
        var mergedUserMarkdown = originalNote.userMarkdown

        if (existingResp != null) {
            val body = existingResp.body.orEmpty()
            val parsed = MarkdownComposer.parse(body)
            val remoteUserContent = MarkdownComposer.userContent(parsed)
            val remoteChanged = RemoteChange.detect(
                localEtag = originalNote.sync.remoteMdEtag,
                localHash = originalNote.sync.remoteMdHash,
                remoteEtag = existingResp.etag,
                remoteBody = body,
            )
            if (remoteChanged) {
                mergedTags = TagMerge.merge(
                    base = originalNote.sync.syncedTags,
                    local = originalNote.tags,
                    remote = parsed.tags ?: emptyList(),
                )
                if (remoteUserContent != null) mergedUserMarkdown = remoteUserContent
            } else if (mergedUserMarkdown == null && remoteUserContent != null) {
                mergedUserMarkdown = remoteUserContent
            }
        }

        val noteWithMerged = originalNote.copy(tags = mergedTags, userMarkdown = mergedUserMarkdown)

        // 3. 组合并上传 Markdown
        val mdContent = MarkdownComposer.compose(
            note = noteWithMerged,
            inkDir = inkDirRelative,
            existing = existingResp?.body,
        )
        val putMdResp = webDavClient.put(targetMdPath, mdContent, "text/markdown; charset=utf-8")

        // 4. 上传各页 SVG
        for ((index, page) in noteWithMerged.pages.withIndex()) {
            val svg = SvgExporter.export(page) { rel -> repository.readAsset(originalNote.id, rel) }
            val fileName = MarkdownComposer.pageFileName(index)
            webDavClient.put("$targetInkDir/$fileName", svg, "image/svg+xml; charset=utf-8")
        }
        // 本地删过页：清理远端多出来的 page-NNN.svg
        for (index in noteWithMerged.pages.size until originalNote.sync.remotePageCount) {
            webDavClient.delete("$targetInkDir/${MarkdownComposer.pageFileName(index)}")
        }

        // 录音只上传一次（按 id 记录），文件较大
        val uploaded = originalNote.sync.uploadedRecordings.toMutableSet()
        for (rec in noteWithMerged.recordings) {
            if (rec.id in uploaded) continue
            val bytes = repository.readAsset(originalNote.id, rec.path) ?: continue
            webDavClient.putBytes("$targetInkDir/${rec.path}", bytes, "audio/mp4")
            uploaded += rec.id
        }

        // 5. 上传原始 ink.json（供灾备恢复）
        val inkJson = NoteJson.encodeToString(Note.serializer(), noteWithMerged)
        webDavClient.put("$targetInkDir/ink.json", inkJson, "application/json; charset=utf-8")

        // 6. 更新本地同步状态
        val syncState = SyncState(
            lastSyncedAt = System.currentTimeMillis(),
            remoteMdPath = targetMdPath,
            remoteMdEtag = putMdResp.etag,
            remoteMdHash = ContentHash.sha256(mdContent),
            syncedTags = noteWithMerged.tags,
            remotePageCount = noteWithMerged.pages.size,
            uploadedRecordings = uploaded.filter { id -> noteWithMerged.recordings.any { it.id == id } },
        )
        repository.save(withSyncState(repository.load(originalNote.id) ?: return, originalNote, noteWithMerged, syncState))
    }

    companion object {
        /**
         * 把同步结果写回笔记。上传要花一段时间，期间笔记可能在编辑器里又改过：
         * 不能用上传时的旧快照覆盖（会丢掉新写的笔迹），而是以磁盘上的最新版本为准，只更新同步状态；
         * 改过的笔记同步时间记为上传的那个版本，保持「待同步」，下次再传。
         * 调用方在笔记已被删除（[latest] 为空）时直接跳过，不能把它写回来。
         */
        fun withSyncState(latest: Note, uploaded: Note, merged: Note, state: SyncState): Note {
            if (latest.updatedAt == uploaded.updatedAt) return merged.copy(sync = state)
            // 远端改过的标签与正文只在本地这段时间没动时采用
            val tags = if (latest.tags == uploaded.tags) merged.tags else latest.tags
            val userMd = if (latest.userMarkdown == uploaded.userMarkdown) merged.userMarkdown else latest.userMarkdown
            // 同步时间取两者较大值：保存时同步状态只进不退（见 NoteRepository），但仍早于这次修改，保持待同步
            val syncedAt = maxOf(uploaded.updatedAt, uploaded.sync.lastSyncedAt)
            return latest.copy(tags = tags, userMarkdown = userMd, sync = state.copy(lastSyncedAt = syncedAt))
        }
    }
}
