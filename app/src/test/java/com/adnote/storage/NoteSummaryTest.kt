package com.adnote.storage

import com.adnote.model.Note
import com.adnote.model.Page
import com.adnote.model.PinLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSummaryTest {

    private val locked = Note(
        title = "机密会议", folder = "工作",
        pages = listOf(Page(width = 10, height = 10, recognizedText = "并购金额一千万")),
        createdAt = 0L, updatedAt = 0L, lockHash = PinLock.hash("1234"),
    )
    private val open = Note(
        title = "普通", folder = "工作",
        pages = listOf(Page(width = 10, height = 10, recognizedText = "并购流程")),
        createdAt = 0L, updatedAt = 0L,
    )

    @Test
    fun lockedNoteMatchesTitleOnly() {
        val items = listOf(NoteSummary.of(locked), NoteSummary.of(open))
        assertEquals(listOf("普通"), NoteSummary.filter(items, "并购").map { it.title })
        assertEquals(listOf("机密会议"), NoteSummary.filter(items, "机密").map { it.title })
        assertEquals(2, NoteSummary.filter(items, "").size)
    }

    @Test
    fun repositoryFilterBehavesTheSame() {
        assertEquals(listOf("普通"), NoteRepository.filter(listOf(locked, open), "并购").map { it.title })
        assertTrue(NoteRepository.filter(listOf(locked, open), "机密").any { it.title == "机密会议" })
    }
}
