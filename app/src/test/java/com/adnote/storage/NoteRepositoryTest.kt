package com.adnote.storage

import com.adnote.model.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NoteRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testCreateAndLoad() {
        val repo = NoteRepository(tempFolder.root)
        val note = repo.create("测试笔记1", "读书/技术", 1404, 1872)

        val loaded = repo.load(note.id)
        assertNotNull(loaded)
        assertEquals("测试笔记1", loaded?.title)
        assertEquals("读书/技术", loaded?.folder)
        assertEquals(1, loaded?.pages?.size)

        val list = repo.list()
        assertEquals(1, list.size)
        assertEquals(note.id, list[0].id)
    }

    @Test
    fun testDeleteAndTombstone() {
        val repo = NoteRepository(tempFolder.root)
        val note = repo.create("要删除的笔记", "临时", 100, 100)

        // Note never synced (lastSyncedAt == 0), no tombstone
        repo.delete(note, remoteInkDir = null)
        assertNull(repo.load(note.id))
        assertTrue(repo.tombstones().isEmpty())

        // Note synced before, should create tombstone
        val syncedNote = repo.create("已同步笔记", "重要", 100, 100)
            .copy(sync = SyncState(lastSyncedAt = 1000L, remoteMdPath = "重要/已同步笔记.md"))
        repo.save(syncedNote)

        repo.delete(syncedNote, remoteInkDir = "重要/_ink/${syncedNote.id}")
        assertNull(repo.load(syncedNote.id))
        val tombstones = repo.tombstones()
        assertEquals(1, tombstones.size)
        assertEquals(syncedNote.id, tombstones[0].noteId)
        assertEquals("重要/已同步笔记.md", tombstones[0].remoteMdPath)

        repo.clearTombstone(syncedNote.id)
        assertTrue(repo.tombstones().isEmpty())
    }

    @Test
    fun testSearch() {
        val repo = NoteRepository(tempFolder.root)
        val n1 = repo.create("Kotlin Coroutines", "工作", 100, 100).copy(tags = listOf("dev", "async"))
        repo.save(n1)

        val n2 = repo.create("Weekly Report", "工作/汇报", 100, 100).copy(tags = listOf("report"))
        repo.save(n2)

        val n3 = repo.create("Shopping List", "生活", 100, 100).copy(tags = listOf("life"))
        repo.save(n3)

        // Search by query
        assertEquals(1, repo.search("coroutines").size)
        assertEquals(n1.id, repo.search("coroutines")[0].id)

        // Search by tag
        assertEquals(1, repo.search("", tag = "report").size)

        // Search by folder (matches subfolder)
        assertEquals(2, repo.search("", folder = "工作").size)
    }

    @Test
    fun testCorruptedNoteHandledGracefully() {
        val repo = NoteRepository(tempFolder.root)
        val n = repo.create("正常笔记", "工作", 100, 100)

        // Inject a corrupted note
        val badDir = File(tempFolder.root, "notes/bad123").apply { mkdirs() }
        File(badDir, "note.json").writeText("{ corrupt json invalid }")

        val list = repo.list()
        assertEquals(1, list.size)
        assertEquals(n.id, list[0].id)
    }

    @Test
    fun testCorruptedNoteRecoversFromBackup() {
        val repo = NoteRepository(tempFolder.root)
        val note = repo.create("重要笔记原版", "工作", 100, 100)
        repo.save(note.copy(title = "重要笔记更新版"))

        val dir = repo.getNoteDir(note.id)
        val noteFile = File(dir, "note.json")
        val bakFile = File(dir, "note.json.bak")
        assertTrue(bakFile.exists())

        // 模拟 note.json 损坏
        noteFile.writeText("{ broken json content")

        val loaded = repo.load(note.id)
        assertNotNull(loaded)
        assertEquals("重要笔记原版", loaded?.title)
    }

    @Test
    fun testAddRemoteCleanupCreatesTombstoneWithoutTouchingNote() {
        val repo = NoteRepository(tempFolder.root)
        val note = repo.create("改名", "工作", 100, 100)
        repo.addRemoteCleanup(note.id, "工作/旧名.md", "工作/_ink/${note.id}")

        val tombs = repo.tombstones()
        assertEquals(1, tombs.size)
        assertTrue(tombs[0].noteId.startsWith("${note.id}-cleanup-"))
        assertEquals("工作/旧名.md", tombs[0].remoteMdPath)
        assertNotNull(repo.load(note.id))

        repo.clearTombstone(tombs[0].noteId)
        assertTrue(repo.tombstones().isEmpty())
    }
}

class NoteRepositoryTrashTest {
    @get:org.junit.Rule
    val tmp = org.junit.rules.TemporaryFolder()

    @org.junit.Test
    fun trashRestoreAndPurge() {
        val repo = NoteRepository(tmp.root)
        val note = repo.create("待删", "收件箱", 800, 1200, now = 1000)
        val synced = note.copy(sync = com.adnote.model.SyncState(lastSyncedAt = 2000, remoteMdPath = "收件箱/待删.md"))
        repo.save(synced)

        repo.moveToTrash(synced, "收件箱/_ink/${note.id}", now = 5000)
        org.junit.Assert.assertNull(repo.load(note.id))
        org.junit.Assert.assertEquals(1, repo.tombstones().size)
        val trashed = repo.listTrash().single()
        org.junit.Assert.assertEquals(5000L, trashed.deletedAt)

        val restored = repo.restore(note.id, now = 6000)!!
        org.junit.Assert.assertTrue(restored.isDirty)
        org.junit.Assert.assertEquals(0L, restored.sync.lastSyncedAt)
        org.junit.Assert.assertTrue(repo.tombstones().isEmpty())
        org.junit.Assert.assertTrue(repo.listTrash().isEmpty())

        repo.moveToTrash(restored, null, now = 0)
        org.junit.Assert.assertEquals(1, repo.purgeExpired(now = 31L * 24 * 3600_000))
        org.junit.Assert.assertTrue(repo.listTrash().isEmpty())
    }

    @org.junit.Test
    fun assetsCopyBetweenNotes() {
        val repo = NoteRepository(tmp.root)
        val a = repo.create("a", "x", 10, 10)
        val b = repo.create("b", "x", 10, 10)
        val path = repo.newAssetPath("images", "jpg")
        repo.assetFile(a.id, path).apply { parentFile.mkdirs() }.writeBytes(byteArrayOf(1, 2, 3))
        org.junit.Assert.assertEquals(path, repo.copyAsset(a.id, path, a.id))
        val copied = repo.copyAsset(a.id, path, b.id)
        org.junit.Assert.assertTrue(copied.startsWith("images/"))
        org.junit.Assert.assertArrayEquals(byteArrayOf(1, 2, 3), repo.readAsset(b.id, copied))
    }

    @Test
    fun asyncSaveIsVisibleImmediatelyAndReachesDisk() {
        val repo = NoteRepository(tmp.root)
        val note = repo.create("原标题", "x", 10, 10)
        repeat(20) { i -> repo.saveAsync(note.copy(title = "标题 $i", updatedAt = i.toLong())) }
        // 还没写完也能读到最新版本（重新打开编辑器不会读到旧内容）
        assertEquals("标题 19", repo.load(note.id)?.title)
        assertEquals("标题 19", repo.list().single().title)
        repo.flush()
        // 换一个仓库实例直接读磁盘：最后一次保存已落盘
        assertEquals("标题 19", NoteRepository(tmp.root).load(note.id)?.title)
    }

    @Test
    fun pendingSaveDoesNotResurrectTrashedNote() {
        val repo = NoteRepository(tmp.root)
        val note = repo.create("要删除", "x", 10, 10)
        repo.saveAsync(note.copy(title = "改过"))
        repo.moveToTrash(note, null)
        repo.flush()
        assertNull(repo.load(note.id))
        assertTrue(repo.list().isEmpty())
        assertEquals("改过", repo.listTrash().single().note.title)
    }

    @Test
    fun filterHelpersWorkOnLoadedList() {
        val repo = NoteRepository(tmp.root)
        val a = repo.create("Kotlin 协程", "工作/项目", 10, 10).copy(tags = listOf("dev"))
        repo.save(a)
        repo.create("购物清单", "生活", 10, 10)
        val all = repo.list()
        assertEquals(listOf(a.id), NoteRepository.filter(all, "协程").map { it.id })
        assertEquals(listOf(a.id), NoteRepository.filter(all, "", folder = "工作").map { it.id })
        assertEquals(listOf("dev"), NoteRepository.tagsOf(all))
        assertTrue("生活" in NoteRepository.foldersOf(all))
    }

    @Test
    fun staleEditorCopyDoesNotRollBackSyncState() {
        val repo = NoteRepository(tmp.root)
        val opened = repo.create("笔记", "x", 10, 10)
        // 编辑器打开期间同步完成
        repo.save(opened.copy(sync = SyncState(lastSyncedAt = 500L, remoteMdPath = "x/笔记.md")))
        // 编辑器拿着旧的同步状态保存新内容
        repo.saveAsync(opened.copy(title = "改过", updatedAt = 900L))
        repo.flush()
        val n = NoteRepository(tmp.root).load(opened.id)!!
        assertEquals("改过", n.title)
        assertEquals(500L, n.sync.lastSyncedAt)
        assertEquals("x/笔记.md", n.sync.remoteMdPath)
        assertTrue(n.isDirty)
    }

    @Test
    fun summariesUseMetaFileAndRegenerateWhenStale() {
        val repo = NoteRepository(tmp.root)
        val n = repo.create("周会", "工作", 10, 10)
        repo.save(n.copy(tags = listOf("team"), coverColor = "#DC2626", updatedAt = 50L))
        val dir = repo.getNoteDir(n.id)
        assertTrue(File(dir, "meta.json").exists())

        val s = repo.summaries().single()
        assertEquals("周会", s.title)
        assertEquals(listOf("team"), s.tags)
        assertEquals(1, s.pageCount)
        assertEquals("#DC2626", s.coverColor)
        assertTrue(s.isDirty)

        // 旧版本写的笔记没有 meta.json：解析一次并补写
        File(dir, "meta.json").delete()
        assertEquals("周会", NoteRepository(tmp.root).summaries().single().title)
        assertTrue(File(dir, "meta.json").exists())

        // 笔记文件比 meta.json 新（别处改过）：以笔记为准
        val noteFile = File(dir, "note.json")
        noteFile.writeText(noteFile.readText().replace("\"周会\"", "\"周会纪要\""))
        File(dir, "meta.json").setLastModified(1_000L)
        noteFile.setLastModified(2_000_000L)
        assertEquals("周会纪要", NoteRepository(tmp.root).summaries().single().title)

        assertEquals(listOf(n.id), NoteSummary.filter(repo.summaries(), "纪要").map { it.id })
    }

    @Test
    fun testImportNoteReplacesTrashAndTombstone() {
        val repo = NoteRepository(tmp.root)
        val note = repo.create("导入", "f", 10, 10)
        val synced = note.copy(sync = SyncState(lastSyncedAt = 100L, remoteMdPath = "f/导入.md"))
        repo.save(synced)
        repo.moveToTrash(synced, "f/_ink/${note.id}")
        assertEquals(1, repo.tombstones().size)
        assertEquals(1, repo.trashCount())

        val fromCloud = synced.copy(title = "云端版本", updatedAt = 999L, sync = SyncState(lastSyncedAt = 999L, remoteMdPath = "f/云端版本.md"))
        repo.importNote(fromCloud)

        assertEquals(0, repo.trashCount())
        assertTrue(repo.tombstones().isEmpty())
        val loaded = repo.load(note.id)!!
        assertEquals("云端版本", loaded.title)
        assertEquals("f/云端版本.md", loaded.sync.remoteMdPath)
    }
}
