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
                remoteMdEtag = "\"etag-initial-on-device\"", // Different ETag!
                syncedTags = listOf("原始本地标签"),
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
        assertEquals(listOf("audio/r1.m4a"), repository.load(created.id)!!.sync.uploadedAssets)

        puts.clear()
        syncEngine.sync(force = true)
        assertEquals(0, puts.count { it.endsWith(".m4a") })
    }

    /** 记录 PUT/DELETE 路径的 dispatcher，GET 一律 404。 */
    private fun assetDispatcher(puts: MutableList<String>, deletes: MutableList<String>) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = java.net.URLDecoder.decode(request.path.orEmpty(), "UTF-8")
            return when (request.method) {
                "MKCOL" -> MockResponse().setResponseCode(201)
                "GET" -> MockResponse().setResponseCode(404)
                "PUT" -> { puts += path; MockResponse().setResponseCode(201).setHeader("ETag", "\"e\"") }
                "DELETE" -> { deletes += path; MockResponse().setResponseCode(204) }
                else -> MockResponse().setResponseCode(200)
            }
        }
    }

    @Test
    fun testImagesAndPdfUploadedOnceAndStaleAssetsDeleted() {
        val puts = java.util.concurrent.CopyOnWriteArrayList<String>()
        val deletes = java.util.concurrent.CopyOnWriteArrayList<String>()
        server.dispatcher = assetDispatcher(puts, deletes)

        val note = repository.create("附件", "f", 10, 10)
        val img = com.adnote.model.ImageItem(path = "images/p.jpg", x = 0f, y = 0f, width = 1f, height = 1f)
        repository.assetFile(note.id, "images/p.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(9)) }
        repository.assetFile(note.id, "document.pdf").writeBytes(byteArrayOf(1))
        repository.save(note.copy(pages = listOf(note.pages[0].copy(images = listOf(img))), pdfPath = "document.pdf"))

        syncEngine.sync()
        assertTrue(puts.any { it.endsWith("/_ink/${note.id}/images/p.jpg") })
        assertTrue(puts.any { it.endsWith("/_ink/${note.id}/document.pdf") })
        assertEquals(setOf("images/p.jpg", "document.pdf"), repository.load(note.id)!!.sync.uploadedAssets.toSet())

        // 删掉图片再同步：不重传 PDF，远端图片被删除
        puts.clear()
        val loaded = repository.load(note.id)!!
        repository.save(loaded.copy(pages = listOf(loaded.pages[0].copy(images = emptyList())), updatedAt = maxOf(loaded.updatedAt, loaded.sync.lastSyncedAt) + 1000))
        syncEngine.sync()
        assertFalse(puts.any { it.endsWith("/document.pdf") })
        assertTrue(deletes.any { it.endsWith("/_ink/${note.id}/images/p.jpg") })
        assertEquals(listOf("document.pdf"), repository.load(note.id)!!.sync.uploadedAssets)
    }

    @Test
    fun testMissingAssetIsSkipped() {
        val puts = java.util.concurrent.CopyOnWriteArrayList<String>()
        server.dispatcher = assetDispatcher(puts, java.util.concurrent.CopyOnWriteArrayList())
        val note = repository.create("缺文件", "f", 10, 10)
        val img = com.adnote.model.ImageItem(path = "images/gone.jpg", x = 0f, y = 0f, width = 1f, height = 1f)
        repository.save(note.copy(pages = listOf(note.pages[0].copy(images = listOf(img)))))

        val result = syncEngine.sync()
        assertEquals(1, result.success)
        assertFalse(puts.any { it.endsWith("gone.jpg") })
        assertTrue(repository.load(note.id)!!.sync.uploadedAssets.isEmpty())
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

    @Test
    fun testTagMergeKeepsLocalAdditions() {
        val remoteMd = """
---
adnote-id: note777
title: 合并
tags: [共同, 远端新增]
---

<!-- adnote:begin 以下内容由 AdNote 自动生成，请勿编辑 -->
<!-- adnote:end -->
""".trimIndent()
        var putBody: String? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.method) {
                "MKCOL" -> MockResponse().setResponseCode(201)
                "GET" -> MockResponse().setResponseCode(200).setBody(remoteMd).setHeader("ETag", "\"pc\"")
                "PUT" -> {
                    if (request.path.orEmpty().endsWith(".md")) putBody = request.body.readUtf8()
                    MockResponse().setResponseCode(201).setHeader("ETag", "\"after\"")
                }
                else -> MockResponse().setResponseCode(200)
            }
        }
        val note = com.adnote.model.Note(
            id = "note777", title = "合并", folder = "收件箱",
            tags = listOf("共同", "本地新增"),
            pages = listOf(com.adnote.model.Page(width = 10, height = 10)),
            createdAt = 1L, updatedAt = 2000L,
            sync = SyncState(lastSyncedAt = 1000L, remoteMdPath = "收件箱/合并.md", remoteMdEtag = "\"dev\"", syncedTags = listOf("共同")),
        )
        repository.save(note)

        syncEngine.sync()

        val synced = repository.load("note777")!!
        assertEquals(listOf("共同", "本地新增", "远端新增"), synced.tags)
        assertEquals(listOf("共同", "本地新增", "远端新增"), synced.sync.syncedTags)
        assertNotNull(synced.sync.remoteMdHash)
        assertTrue(putBody!!.contains("  - 本地新增"))
        assertTrue(putBody!!.contains("  - 远端新增"))
    }

    @Test
    fun testPutWithoutEtagFallsBackToHash() {
        val store = ConcurrentHashMap<String, String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when (request.method) {
                    "MKCOL" -> MockResponse().setResponseCode(201)
                    "PUT" -> {
                        store[path] = request.body.readUtf8()
                        MockResponse().setResponseCode(201)
                    }
                    "GET" -> {
                        val body = store[path]
                        if (body == null) MockResponse().setResponseCode(404)
                        else MockResponse().setResponseCode(200).setBody(body)
                            .setHeader("ETag", "\"" + body.hashCode() + "\"")
                    }
                    else -> MockResponse().setResponseCode(200)
                }
            }
        }
        repository.save(
            com.adnote.model.Note(
                id = "noteEtag", title = "无ETag", folder = "收件箱", tags = listOf("a"),
                pages = listOf(com.adnote.model.Page(width = 10, height = 10)),
                createdAt = 1L, updatedAt = 1000L,
            )
        )
        syncEngine.sync()
        var n = repository.load("noteEtag")!!

        // 第 2 次：改标签
        repository.save(n.copy(tags = listOf("a", "b"), updatedAt = maxOf(n.updatedAt, n.sync.lastSyncedAt) + 1000))
        syncEngine.sync()
        n = repository.load("noteEtag")!!
        assertNull(n.sync.remoteMdEtag)

        // 第 3 次：改正文
        repository.save(n.copy(userMarkdown = "本地新正文", updatedAt = maxOf(n.updatedAt, n.sync.lastSyncedAt) + 1000))
        syncEngine.sync()
        n = repository.load("noteEtag")!!
        assertEquals("本地新正文", n.userMarkdown)
    }

    @Test
    fun testFailedMoveSchedulesRemoteDelete() {
        val deleted = java.util.concurrent.CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = java.net.URLDecoder.decode(request.path.orEmpty(), "UTF-8")
                return when (request.method) {
                    "MKCOL" -> MockResponse().setResponseCode(201)
                    "MOVE" -> MockResponse().setResponseCode(500)
                    "GET" -> MockResponse().setResponseCode(404)
                    "PUT" -> MockResponse().setResponseCode(201).setHeader("ETag", "\"e\"")
                    "DELETE" -> { deleted += path; MockResponse().setResponseCode(204) }
                    else -> MockResponse().setResponseCode(200)
                }
            }
        }
        val note = com.adnote.model.Note(
            id = "mv1", title = "新名", folder = "工作",
            pages = listOf(com.adnote.model.Page(width = 10, height = 10)),
            createdAt = 1L, updatedAt = 2000L,
            sync = SyncState(lastSyncedAt = 1000L, remoteMdPath = "工作/旧名.md", remoteMdEtag = "\"x\""),
        )
        repository.save(note)

        val result = syncEngine.sync()
        assertEquals(1, result.success)
        // 同步结束前就尝试清理旧路径
        assertTrue(deleted.any { it.endsWith("/AdNote/工作/旧名.md") })
        assertTrue(repository.tombstones().isEmpty())
        assertEquals("工作/新名.md", repository.load("mv1")!!.sync.remoteMdPath)
    }

    /** 内存 WebDAV：按路径存内容与 ETag，支持 PROPFIND Depth 1。 */
    private inner class MemoryDav : Dispatcher() {
        val files = ConcurrentHashMap<String, Pair<String, String>>() // 相对根目录路径 -> (内容, etag)
        private fun rel(request: RecordedRequest) =
            java.net.URLDecoder.decode(request.path.orEmpty(), "UTF-8").removePrefix("/AdNote").trim('/')

        override fun dispatch(request: RecordedRequest): MockResponse {
            val p = rel(request)
            return when (request.method) {
                "MKCOL" -> MockResponse().setResponseCode(201)
                "GET" -> files[p]?.let { MockResponse().setResponseCode(200).setBody(it.first).setHeader("ETag", it.second) }
                    ?: MockResponse().setResponseCode(404)
                "PUT" -> {
                    val etag = "\"v${files.size + 1}-${System.nanoTime()}\""
                    files[p] = request.body.readUtf8() to etag
                    MockResponse().setResponseCode(201).setHeader("ETag", etag)
                }
                "DELETE" -> { files.remove(p); MockResponse().setResponseCode(204) }
                "PROPFIND" -> {
                    val children = files.keys.filter { it.substringBeforeLast('/', "") == p }
                    val xml = buildString {
                        append("""<?xml version="1.0"?><D:multistatus xmlns:D="DAV:">""")
                        append("<D:response><D:href>/AdNote/$p/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>")
                        for (c in children) {
                            append("<D:response><D:href>/AdNote/$c</D:href><D:propstat><D:prop><D:resourcetype/>")
                            append("<D:getetag>${files[c]!!.second}</D:getetag></D:prop></D:propstat></D:response>")
                        }
                        append("</D:multistatus>")
                    }
                    MockResponse().setResponseCode(207).setBody(xml)
                }
                else -> MockResponse().setResponseCode(200)
            }
        }
    }

    /** 模拟在 Obsidian 里改 tags、在用户区加正文，生成区原样保留。 */
    private fun withObsidianEdits(md: String, tags: String, userText: String): String {
        val fm = md.substringBefore("\n---\n", "")
        val body = md.substringAfter("\n---\n")
        val newFm = fm.lines().filterNot { it.startsWith("tags:") || it.startsWith("  - ") }.joinToString("\n") + "\ntags: [$tags]"
        return "$newFm\n---\n$userText\n\n" + body.substring(body.indexOf("<!-- adnote:begin"))
    }

    @Test
    fun testPullBringsObsidianEditsToCleanNote() {
        val dav = MemoryDav()
        server.dispatcher = dav
        val created = repository.create("拉取", "工作", 10, 10)
        repository.save(created.copy(tags = listOf("设备")))
        assertEquals(1, syncEngine.sync().success)
        val mdPath = repository.load(created.id)!!.sync.remoteMdPath!!
        assertFalse(repository.load(created.id)!!.isDirty)

        // 在 Obsidian 里改标签、加正文
        val (md, _) = dav.files[mdPath]!!
        dav.files[mdPath] = withObsidianEdits(md, "设备, 电脑", "电脑上补充的想法") to "\"edited-on-pc\""

        val result = syncEngine.sync()
        assertEquals(1, result.pulled)
        val pulled = repository.load(created.id)!!
        assertEquals(listOf("设备", "电脑"), pulled.tags)
        assertEquals("电脑上补充的想法", pulled.userMarkdown)
        assertEquals("\"edited-on-pc\"", pulled.sync.remoteMdEtag)

        // 再同步一次：ETag 相同，不再拉取
        assertEquals(0, syncEngine.sync().pulled)
    }

    @Test
    fun testPullSkipsLocallyDirtyNote() {
        val dav = MemoryDav()
        server.dispatcher = dav
        val note = repository.create("脏笔记", "工作", 10, 10)
        syncEngine.sync()
        val mdPath = repository.load(note.id)!!.sync.remoteMdPath!!
        val (md, _) = dav.files[mdPath]!!
        dav.files[mdPath] = withObsidianEdits(md, "电脑", "正文") to "\"pc\""

        // 本地又改了：这篇会走推送合并，不走拉取
        val local = repository.load(note.id)!!
        repository.save(local.copy(tags = listOf("本地"), updatedAt = maxOf(local.updatedAt, local.sync.lastSyncedAt) + 1000))
        val result = syncEngine.sync()
        assertEquals(0, result.pulled)
        assertEquals(1, result.success)
        assertEquals(listOf("本地", "电脑"), repository.load(note.id)!!.tags)
    }
}
