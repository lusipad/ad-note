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
    /** 拉取阶段从 Obsidian 端带回修改的笔记数。 */
    val pulled: Int = 0,
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
        // 1. 处理已删除笔记与改名残留的墓碑
        processTombstones()?.let { return SyncResult(total = 0, success = 0, failed = 1, firstError = it) }

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

        // 3. 拉取：本地没改、远端可能在 Obsidian 里改过的笔记
        val pulled = try {
            pullRemoteEdits(allNotes.filter { it !in dirtyNotes && it.sync.remoteMdPath != null })
        } catch (e: WebDavAuthException) {
            return SyncResult(total = dirtyNotes.size, success = successCount, failed = failedCount, firstError = e.message)
        }

        // 本次同步中登记的改名残留，顺手清掉
        processTombstones()
        return SyncResult(
            total = dirtyNotes.size,
            success = successCount,
            failed = failedCount,
            firstError = firstError,
            pulled = pulled,
        )
    }

    /**
     * 按远端文件夹各做一次 PROPFIND 拿 ETag；ETag 不同或拿不到 ETag 的再 GET，
     * 用内容哈希确认变化后合并标签、采用远端正文。合并后标签与远端不同时标记待推送。
     */
    private fun pullRemoteEdits(notes: List<Note>): Int {
        var pulled = 0
        val byFolder = notes.groupBy { it.sync.remoteMdPath!!.substringBeforeLast('/', "") }
        for ((folder, group) in byFolder) {
            val entries = try {
                webDavClient.list(folder).associateBy { it.path }
            } catch (e: WebDavAuthException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            for (note in group) {
                val mdPath = note.sync.remoteMdPath!!
                val entry = entries[mdPath] ?: continue
                val etagSame = entry.etag != null && note.sync.remoteMdEtag != null && entry.etag == note.sync.remoteMdEtag
                if (etagSame) continue
                val resp = runCatching { webDavClient.get(mdPath) }.getOrNull() ?: continue
                val body = resp.body.orEmpty()
                if (!RemoteChange.detect(note.sync.remoteMdEtag, note.sync.remoteMdHash, resp.etag, body)) continue

                val latest = repository.load(note.id) ?: continue
                if (latest.isDirty) continue
                val parsed = MarkdownComposer.parse(body)
                val remoteTags = parsed.tags ?: emptyList()
                val tags = TagMerge.merge(latest.sync.syncedTags, latest.tags, remoteTags)
                val userMd = MarkdownComposer.userContent(parsed) ?: latest.userMarkdown
                val needsPush = tags != remoteTags
                repository.save(
                    latest.copy(
                        tags = tags,
                        userMarkdown = userMd,
                        updatedAt = if (needsPush) System.currentTimeMillis() else latest.updatedAt,
                        sync = latest.sync.copy(
                            remoteMdEtag = resp.etag ?: latest.sync.remoteMdEtag,
                            remoteMdHash = ContentHash.sha256(body),
                            syncedTags = if (needsPush) latest.sync.syncedTags else tags,
                        ),
                    )
                )
                pulled++
            }
        }
        return pulled
    }

    /** 逐条删除墓碑对应的远端文件；认证失败返回错误信息，其余失败留待下次。 */
    private fun processTombstones(): String? {
        for (tombstone in repository.tombstones()) {
            try {
                tombstone.remoteMdPath?.let { webDavClient.delete(it) }
                tombstone.remoteInkDir?.let { webDavClient.delete(it) }
                repository.clearTombstone(tombstone.noteId)
            } catch (e: WebDavAuthException) {
                return e.message
            } catch (_: Exception) {
                // 删除失败留待下次同步继续尝试
            }
        }
        return null
    }

    private fun syncSingleNote(originalNote: Note, targetMdPath: String) {
        val oldMdPath = originalNote.sync.remoteMdPath
        val oldInkDir = originalNote.sync.remoteMdPath?.let {
            val folder = it.substringBeforeLast('/', "")
            if (folder.isEmpty()) "_ink/${originalNote.id}" else "$folder/_ink/${originalNote.id}"
        }
        val targetInkDir = RemotePaths.inkDir(originalNote)
        val inkDirRelative = RemotePaths.inkDirRelative(originalNote)

        // 1. 标题或文件夹发生变化，尝试 MOVE；失败则登记旧路径待删除
        var movedInk = true
        if (oldMdPath != null && oldMdPath != targetMdPath) {
            val movedMd = runCatching { webDavClient.move(oldMdPath, targetMdPath) }.getOrDefault(false)
            if (!movedMd) repository.addRemoteCleanup(originalNote.id, oldMdPath, null)
            if (oldInkDir != null && oldInkDir != targetInkDir) {
                movedInk = runCatching { webDavClient.move(oldInkDir, targetInkDir) }.getOrDefault(false)
                if (!movedInk) repository.addRemoteCleanup(originalNote.id, null, oldInkDir)
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

        // 附件（录音、图片、背景、PDF）每个只传一次；本地删掉的在远端也删掉；
        // 笔迹目录 MOVE 失败时远端等于空目录，全部重传
        val previouslyUploaded = if (movedInk) NoteAssets.previouslyUploaded(originalNote) else emptySet()
        val referenced = NoteAssets.referenced(noteWithMerged)
        val uploadedAssets = ArrayList<String>()
        for (rel in referenced) {
            if (rel in previouslyUploaded) { uploadedAssets += rel; continue }
            val file = repository.assetFile(originalNote.id, rel)
            if (!file.isFile) continue
            webDavClient.putFile("$targetInkDir/$rel", file, NoteAssets.mimeType(rel))
            uploadedAssets += rel
        }
        for (stale in previouslyUploaded - referenced.toSet()) {
            runCatching { webDavClient.delete("$targetInkDir/$stale") }
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
            uploadedAssets = uploadedAssets,
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
