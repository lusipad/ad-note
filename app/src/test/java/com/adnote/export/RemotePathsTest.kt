package com.adnote.export

import com.adnote.model.Note
import com.adnote.model.Page
import com.adnote.model.SyncState
import org.junit.Assert.assertEquals
import org.junit.Test

class RemotePathsTest {

    private fun note(id: String, title: String, folder: String, createdAt: Long, remoteMdPath: String? = null) = Note(
        id = id, title = title, folder = folder,
        pages = listOf(Page(width = 10, height = 10)),
        createdAt = createdAt, updatedAt = createdAt,
        sync = if (remoteMdPath == null) SyncState() else SyncState(lastSyncedAt = createdAt, remoteMdPath = remoteMdPath),
    )

    @Test
    fun ownerOfPlainPathKeepsItEvenWhenOlderNoteMovesIn() {
        // A 更早创建、刚从「工作」并入「读书」；B 更晚创建、已同步在 读书/会议记录.md
        val mover = note("aaaa1111", "会议记录", "读书", createdAt = 100L, remoteMdPath = "工作/会议记录.md")
        val incumbent = note("bbbb2222", "会议记录", "读书", createdAt = 200L, remoteMdPath = "读书/会议记录.md")

        val paths = RemotePaths.assignMdPaths(listOf(mover, incumbent))

        assertEquals("读书/会议记录.md", paths["bbbb2222"])
        assertEquals("读书/会议记录 (aaaa).md", paths["aaaa1111"])
    }

    @Test
    fun withoutOwnerOldestNoteGetsPlainPath() {
        val older = note("aaaa1111", "会议记录", "读书", createdAt = 100L)
        val newer = note("bbbb2222", "会议记录", "读书", createdAt = 200L)

        val paths = RemotePaths.assignMdPaths(listOf(newer, older))

        assertEquals("读书/会议记录.md", paths["aaaa1111"])
        assertEquals("读书/会议记录 (bbbb).md", paths["bbbb2222"])
    }

    @Test
    fun ownerOfSuffixedPathKeepsIt() {
        // A 更早创建，但上次同步时已经拿到了带后缀的路径；新建的 B 不该把 A 挤走
        val suffixedOwner = note("aaaa1111", "会议记录", "读书", createdAt = 100L, remoteMdPath = "读书/会议记录 (aaaa).md")
        val fresh = note("bbbb2222", "会议记录", "读书", createdAt = 200L)

        val paths = RemotePaths.assignMdPaths(listOf(suffixedOwner, fresh))

        assertEquals("读书/会议记录 (aaaa).md", paths["aaaa1111"])
        assertEquals("读书/会议记录.md", paths["bbbb2222"])
    }

    @Test
    fun collisionDetectionIgnoresCase() {
        val owner = note("aaaa1111", "Readme", "工作", createdAt = 200L, remoteMdPath = "工作/Readme.md")
        val other = note("bbbb2222", "readme", "工作", createdAt = 100L)

        val paths = RemotePaths.assignMdPaths(listOf(other, owner))

        assertEquals("工作/Readme.md", paths["aaaa1111"])
        assertEquals("工作/readme (bbbb).md", paths["bbbb2222"])
    }
}
