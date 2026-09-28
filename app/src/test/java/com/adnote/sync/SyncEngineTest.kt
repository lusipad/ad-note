package com.adnote.sync

import com.adnote.model.SyncState
import com.adnote.storage.NoteRepository
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.ConcurrentHashMap

class SyncEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var repository: NoteRepository
    private lateinit var webDavClient: WebDavClient
    private lateinit var syncEngine: SyncEngine

    private val uploadedFiles = ConcurrentHashMap<String, String>()

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()

        repository = NoteRepository(tempFolder.root)
        webDavClient = WebDavClient(
            baseUrl = server.url("/").toString(),
            username = "user",
            password = "pwd",
            remoteRootDir = "AdNote"
        )
        syncEngine = SyncEngine(repository, webDavClient)
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun testSyncNewNote() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = java.net.URLDecoder.decode(request.path.orEmpty(), "UTF-8")
                return when (request.method) {
                    "MKCOL" -> MockResponse().setResponseCode(201)
                    "GET" -> {
                        val content = uploadedFiles[path]
                        if (content != null) {
                            MockResponse().setResponseCode(200).setBody(content).setHeader("ETag", "\"etag-1\"")
                        } else {
                            MockResponse().setResponseCode(404)
                        }
                    }
                    "PUT" -> {
                        val body = request.body.readUtf8()
                        uploadedFiles[path] = body
                        MockResponse().setResponseCode(201).setHeader("ETag", "\"etag-new\"")
                    }
                    else -> MockResponse().setResponseCode(200)
                }
            }
        }

        val note = repository.create("新笔记", "工作", 800, 1200)
        assertTrue(note.isDirty)

        val result = syncEngine.sync()
        assertEquals(1, result.total)
        assertEquals(1, result.success)
        assertEquals(0, result.failed)
        assertNull(result.firstError)

        val updated = repository.load(note.id)
        assertNotNull(updated)
        assertFalse(updated!!.isDirty)
        assertEquals("\"etag-new\"", updated.sync.remoteMdEtag)
        assertEquals("工作/新笔记.md", updated.sync.remoteMdPath)

        // Verify that MD, SVG, and ink.json were uploaded to the mock server
        assertTrue(uploadedFiles.keys.any { it.endsWith("/工作/新笔记.md") })
        assertTrue(uploadedFiles.keys.any { it.endsWith("/page-001.svg") })
        assertTrue(uploadedFiles.keys.any { it.endsWith("/ink.json") })
    }

    @Test
    fun testSyncTombstone() {
        val deletedPaths = ArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = java.net.URLDecoder.decode(request.path.orEmpty(), "UTF-8")
                if (request.method == "DELETE") {
                    deletedPaths.add(path)
                    return MockResponse().setResponseCode(204)
                }
                return MockResponse().setResponseCode(200)
            }
        }

        val note = repository.create("已同步要删的笔记", "临时", 100, 100).copy(
            sync = SyncState(lastSyncedAt = 100L, remoteMdPath = "临时/已同步要删的笔记.md")
        )
        repository.save(note)

        repository.delete(note, remoteInkDir = "临时/_ink/${note.id}")
        assertEquals(1, repository.tombstones().size)

        val result = syncEngine.sync()
        assertEquals(0, result.failed)
        assertTrue(repository.tombstones().isEmpty())
        assertTrue(deletedPaths.any { it.contains("临时/已同步要删的笔记.md") })
        assertTrue(deletedPaths.any { it.contains("临时/_ink/${note.id}") })
    }

    @Test
    fun testSyncAuthFailureAborts() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse().setResponseCode(401)
            }
        }

        repository.create("笔记1", "工作", 100, 100)
        val result = syncEngine.sync()
        assertTrue(result.failed > 0)
        assertTrue(result.firstError?.contains("认证失败") == true)
    }

    @Test
    fun testObsidianTagMerge() {
        val existingMdOnObsidian = """
---
adnote-id: note999
title: 旧笔记
tags: [Obsidian新标签1, 标签2]
---

用户在此处手写了文字。

<!-- adnote:begin 以下内容由 AdNote 自动生成，请勿编辑 -->
## 第 1 页
![第 1 页](_ink/note999/page-001.svg)
<!-- adnote:end -->
""".trimIndent()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.method) {
                    "MKCOL" -> MockResponse().setResponseCode(201)
                    "GET" -> MockResponse().setResponseCode(200)
                        .setBody(existingMdOnObsidian)
                        .setHeader("ETag", "\"etag-modified-on-pc\"")
                    "PUT" -> MockResponse().setResponseCode(201).setHeader("ETag", "\"etag-final\"")
                    else -> MockResponse().setResponseCode(200)
                }
            }
        }

        val note = com.adnote.model.Note(
            id = "note999",
            title = "旧笔记",
            folder = "收件箱",
            tags = listOf("原始本地标签"),
            pages = listOf(com.adnote.model.Page(width = 100, height = 100)),
            createdAt = 1000L,
            updatedAt = 2000L, // dirty
            sync = SyncState(
                lastSyncedAt = 1000L,
                remoteMdPath = "收件箱/旧笔记.md",
                remoteMdEtag = "\"etag-initial-on-device\"" // Different ETag!
            )
        )
        repository.save(note)

        val result = syncEngine.sync()
        assertEquals(1, result.total)
        assertEquals(1, result.success)

        val synced = repository.load("note999")
        assertNotNull(synced)
        // Tags should have been updated from remote Obsidian markdown!
        assertEquals(listOf("Obsidian新标签1", "标签2"), synced!!.tags)
    }

    @Test
    fun testSyncDeletesStalePageSvgs() {
        val deletedPaths = ArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = java.net.URLDecoder.decode(request.path.orEmpty(), "UTF-8")
                return when (request.method) {
                    "GET" -> MockResponse().setResponseCode(404)
                    "DELETE" -> { deletedPaths.add(path); MockResponse().setResponseCode(204) }
                    else -> MockResponse().setResponseCode(201).setHeader("ETag", "\"e\"")
                }
            }
        }

        // 上次同步了 3 页，本地删到只剩 1 页
        val created = repository.create("删页", "收件箱", 800, 1200)
        val note = created.copy(
            updatedAt = created.updatedAt + 10,
            sync = SyncState(lastSyncedAt = created.updatedAt, remoteMdPath = "收件箱/删页.md", remotePageCount = 3),
        )
        repository.save(note)

        val result = syncEngine.sync()
        assertEquals(1, result.success)
        assertEquals(2, deletedPaths.size)
        assertTrue(deletedPaths.any { it.endsWith("/_ink/${note.id}/page-002.svg") })
        assertTrue(deletedPaths.any { it.endsWith("/_ink/${note.id}/page-003.svg") })
        assertEquals(1, repository.load(note.id)!!.sync.remotePageCount)
    }

    @Test
    fun testRecordingsUploadOnce() {
        val puts = ArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = java.net.URLDecoder.decode(request.path.orEmpty(), "UTF-8")
                if (request.method == "PUT") puts += path
                return when (request.method) {
                    "GET" -> MockResponse().setResponseCode(404)
                    else -> MockResponse().setResponseCode(201)
                }
            }
        }
        val created = repository.create("录音笔记", "收件箱", 800, 1200)
        repository.assetFile(created.id, "audio/r1.m4a").apply { parentFile.mkdirs() }.writeBytes(ByteArray(16))
        repository.save(created.copy(recordings = listOf(com.adnote.model.Recording(id = "r1", path = "audio/r1.m4a", createdAt = 0, durationMs = 1000))))

        syncEngine.sync()
        assertEquals(1, puts.count { it.endsWith("/audio/r1.m4a") })
        assertEquals(listOf("r1"), repository.load(created.id)!!.sync.uploadedRecordings)

        puts.clear()
        syncEngine.sync(force = true)
        assertEquals(0, puts.count { it.endsWith(".m4a") })
    }

    @org.junit.Test
    fun syncResultKeepsEditsMadeDuringUpload() {
        val uploaded = com.adnote.model.Note(title = "t", folder = "f", tags = listOf("a"), pages = listOf(com.adnote.model.Page(width = 10, height = 10)), createdAt = 0L, updatedAt = 100L)
        val merged = uploaded.copy(tags = listOf("a", "from-obsidian"))
        val state = com.adnote.model.SyncState(lastSyncedAt = 500L, remoteMdPath = "f/t.md")

        // 上传期间没改过：写入合并后的标签与同步状态，不再是待同步
        val unchanged = SyncEngine.withSyncState(uploaded, uploaded, merged, state)
        org.junit.Assert.assertEquals(listOf("a", "from-obsidian"), unchanged.tags)
        org.junit.Assert.assertFalse(unchanged.isDirty)

        // 上传期间又写了一页：保留新内容，并且仍然待同步
        val edited = uploaded.copy(updatedAt = 300L, pages = uploaded.pages + com.adnote.model.Page(width = 10, height = 10))
        val result = SyncEngine.withSyncState(edited, uploaded, merged, state)
        org.junit.Assert.assertEquals(edited.pages.size, result.pages.size)
        org.junit.Assert.assertEquals(listOf("a", "from-obsidian"), result.tags)
        org.junit.Assert.assertEquals("f/t.md", result.sync.remoteMdPath)
        org.junit.Assert.assertTrue(result.isDirty)

        // 上传期间本地改了标签：以本地为准
        val retagged = edited.copy(tags = listOf("b"))
        org.junit.Assert.assertEquals(listOf("b"), SyncEngine.withSyncState(retagged, uploaded, merged, state).tags)
    }

    @org.junit.Test
    fun syncBidirectionalObsidianUserMarkdown() {
        val note = repository.create("读书笔记", "收件箱", 800, 1200)
        val remoteMd = """
---
adnote-id: ${note.id}
title: 读书笔记
tags: []
created: 2026-03-01T00:00:00+08:00
updated: 2026-03-01T00:00:00+08:00
---

这是在电脑 Obsidian 里补充的深度思考与卡片摘录。

<!-- adnote:begin 以下内容由 AdNote 自动生成，请勿编辑 -->
## 第 1 页
![第 1 页](_ink/${note.id}/page-001.svg)
<!-- adnote:end -->
""".trimIndent()

        uploadedFiles["/AdNote/收件箱/读书笔记.md"] = remoteMd

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = java.net.URLDecoder.decode(request.path.orEmpty(), "UTF-8")
                return when (request.method) {
                    "MKCOL" -> MockResponse().setResponseCode(201)
                    "GET" -> {
                        val content = uploadedFiles[path]
                        if (content != null) {
                            MockResponse().setResponseCode(200).setBody(content).setHeader("ETag", "\"remote-etag-1\"")
                        } else {
                            MockResponse().setResponseCode(404)
                        }
                    }
                    "PUT" -> {
                        val body = request.body.readUtf8()
                        uploadedFiles[path] = body
                        MockResponse().setResponseCode(201).setHeader("ETag", "\"remote-etag-2\"")
                    }
                    else -> MockResponse().setResponseCode(200)
                }
            }
        }

        val result = syncEngine.sync(force = true)
        assertEquals(1, result.success)

        val updated = repository.load(note.id)
        assertNotNull(updated)
        assertEquals("这是在电脑 Obsidian 里补充的深度思考与卡片摘录。", updated?.userMarkdown)
        assertTrue(updated?.searchableText()?.contains("深度思考与卡片摘录") == true)
    }
}
