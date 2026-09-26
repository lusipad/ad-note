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
}
