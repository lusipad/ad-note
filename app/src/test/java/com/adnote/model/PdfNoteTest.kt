package com.adnote.model

import com.adnote.storage.NoteRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class PdfNoteTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testPdfNoteSerialization() {
        val note = Note(
            title = "学术论文",
            pages = listOf(Page(width = 1200, height = 1600)),
            createdAt = 1000L,
            updatedAt = 1000L,
            pdfPath = "document.pdf"
        )
        assertTrue(note.isPdf)

        val json = NoteJson.encodeToString(Note.serializer(), note)
        val decoded = NoteJson.decodeFromString(Note.serializer(), json)

        assertEquals("document.pdf", decoded.pdfPath)
        assertTrue(decoded.isPdf)
    }

    @Test
    fun testBackwardCompatibilityWithoutPdfPath() {
        val legacyJson = """
            {
              "id": "legacy01",
              "title": "老旧普通笔记",
              "folder": "收件箱",
              "tags": [],
              "pages": [],
              "createdAt": 1000,
              "updatedAt": 1000
            }
        """.trimIndent()

        val decoded = NoteJson.decodeFromString(Note.serializer(), legacyJson)
        assertFalse(decoded.isPdf)
        assertEquals(null, decoded.pdfPath)
    }

    @Test
    fun testRepositoryCreatePdfNote() {
        val repo = NoteRepository(tempFolder.root)
        val dummyPdfBytes = "%PDF-1.4 dummy test content".toByteArray(Charsets.UTF_8)
        val pages = listOf(
            Page(width = 1000, height = 1400),
            Page(width = 1000, height = 1400)
        )

        val note = repo.createPdfNote(
            title = "测试PDF文档",
            folder = "论文",
            pdfSource = ByteArrayInputStream(dummyPdfBytes),
            pages = pages
        )

        assertTrue(note.isPdf)
        assertEquals(2, note.pages.size)

        val pdfFile = repo.getPdfFile(note)
        assertNotNull(pdfFile)
        assertTrue(pdfFile!!.exists())
        assertEquals("%PDF-1.4 dummy test content", pdfFile.readText())

        // Ensure loaded note preserves pdfPath
        val loaded = repo.load(note.id)
        assertNotNull(loaded)
        assertTrue(loaded!!.isPdf)
        assertEquals("document.pdf", loaded.pdfPath)
    }
}
