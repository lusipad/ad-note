package com.adnote.sync

import com.adnote.model.ImageItem
import com.adnote.model.Note
import com.adnote.model.Page
import com.adnote.model.Recording
import com.adnote.model.SyncState
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteAssetsTest {

    private fun note() = Note(
        title = "t", folder = "f",
        pages = listOf(
            Page(width = 10, height = 10, images = listOf(ImageItem(path = "images/a.jpg", x = 0f, y = 0f, width = 1f, height = 1f)), backgroundImage = "backgrounds/bg.png"),
            Page(width = 10, height = 10, images = listOf(ImageItem(path = "images/a.jpg", x = 0f, y = 0f, width = 1f, height = 1f))),
        ),
        createdAt = 0L, updatedAt = 0L,
        pdfPath = "document.pdf",
        recordings = listOf(Recording(id = "r1", path = "audio/r1.m4a", createdAt = 0L, durationMs = 1L)),
        sync = SyncState(uploadedRecordings = listOf("r1"), uploadedAssets = listOf("images/old.jpg")),
    )

    @Test
    fun referencedIsDistinctAndOrdered() {
        assertEquals(listOf("audio/r1.m4a", "images/a.jpg", "backgrounds/bg.png", "document.pdf"), NoteAssets.referenced(note()))
    }

    @Test
    fun mimeTypes() {
        assertEquals("audio/mp4", NoteAssets.mimeType("audio/x.m4a"))
        assertEquals("image/jpeg", NoteAssets.mimeType("images/x.jpg"))
        assertEquals("image/png", NoteAssets.mimeType("b.PNG"))
        assertEquals("application/pdf", NoteAssets.mimeType("document.pdf"))
        assertEquals("application/octet-stream", NoteAssets.mimeType("x.bin"))
    }

    @Test
    fun previouslyUploadedMergesLegacyRecordingIds() {
        assertEquals(setOf("images/old.jpg", "audio/r1.m4a"), NoteAssets.previouslyUploaded(note()))
    }
}
