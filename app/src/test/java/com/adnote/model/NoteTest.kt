package com.adnote.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteTest {

    @Test
    fun testSerializationRoundTrip() {
        val note = Note(
            id = "note123",
            title = "测试笔记",
            folder = "工作/2026",
            tags = listOf("会议", "草稿"),
            pages = listOf(
                Page(
                    id = "p1",
                    width = 1404,
                    height = 1872,
                    strokes = listOf(
                        Stroke(
                            id = "s1",
                            points = listOf(
                                InkPoint(10f, 20f, 0.6f, 1000L),
                                InkPoint(15f, 25f, 0.8f, 1010L)
                            ),
                            width = 3.5f
                        )
                    ),
                    recognizedText = "会议纪要第一条"
                )
            ),
            createdAt = 1700000000000L,
            updatedAt = 1700000050000L,
            sync = SyncState(
                lastSyncedAt = 1700000010000L,
                remoteMdPath = "工作/2026/测试笔记.md",
                remoteMdEtag = "\"etag123\""
            )
        )

        val json = NoteJson.encodeToString(Note.serializer(), note)
        val decoded = NoteJson.decodeFromString(Note.serializer(), json)

        assertEquals(note, decoded)
        assertTrue(decoded.isDirty)
        assertTrue(decoded.searchableText().contains("测试笔记"))
        assertTrue(decoded.searchableText().contains("#会议"))
        assertTrue(decoded.searchableText().contains("会议纪要第一条"))
    }

    @Test
    fun testIsDirty() {
        val cleanNote = Note(
            title = "已同步",
            pages = emptyList(),
            createdAt = 100L,
            updatedAt = 200L,
            sync = SyncState(lastSyncedAt = 200L)
        )
        assertFalse(cleanNote.isDirty)

        val dirtyNote = cleanNote.copy(updatedAt = 201L)
        assertTrue(dirtyNote.isDirty)
    }
}
