package com.adnote.sync

import com.adnote.model.Note
import com.adnote.model.NoteJson
import com.adnote.model.Page
import com.adnote.model.Recording
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

class RestoreEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var repository: NoteRepository
    private lateinit var engine: RestoreEngine

    /** 目录树：相对路径 -> (子项名, 是否目录) 列表；文件：相对路径 -> 内容字节。 */
    private val dirs = HashMap<String, List<Pair<String, Boolean>>>()
    private val files = HashMap<String, ByteArray>()

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = java.net.URLDecoder.decode(request.path.orEmpty(), "UTF-8").removePrefix("/AdNote").trim('/')
                return when (request.method) {
                    "PROPFIND" -> {
                        val children = dirs[p] ?: return MockResponse().setResponseCode(404)
                        val xml = buildString {
                            append("""<?xml version="1.0"?><D:multistatus xmlns:D="DAV:">""")
                            append("<D:response><D:href>/AdNote/$p/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>")
                            for ((name, isDir) in children) {
                                val child = if (p.isEmpty()) name else "$p/$name"
                                append("<D:response><D:href>/AdNote/$child${if (isDir) "/" else ""}</D:href><D:propstat><D:prop>")
                                append("<D:resourcetype>${if (isDir) "<D:collection/>" else ""}</D:resourcetype><D:getetag>\"e\"</D:getetag>")
                                append("</D:prop></D:propstat></D:response>")
                            }
                            append("</D:multistatus>")
                        }
                        MockResponse().setResponseCode(207).setBody(xml)
                    }
                    // OkHttp 会把 URL 里的 ".." 折叠掉：越界测试的恶意文件在折叠后的路径上同样能拿到
                    "GET" -> (files[p] ?: files.entries.firstOrNull { p.endsWith("evil.jpg") && it.key.endsWith("evil.jpg") }?.value)
                        ?.let { MockResponse().setResponseCode(200).setBody(okio.Buffer().write(it)) }
                        ?: MockResponse().setResponseCode(404)
                    else -> MockResponse().setResponseCode(200)
                }
            }
        }
        repository = NoteRepository(tempFolder.root)
        engine = RestoreEngine(repository, WebDavClient(server.url("/").toString(), "u", "p", "AdNote"))
    }

    @After
    fun teardown() = server.shutdown()

    /** [mdName] 为 null 表示远端没有 md；md 的 front matter 写 adnote-id: [mdId]。 */
    private fun putRemoteNote(
        folder: String,
        note: Note,
        extra: Map<String, ByteArray> = emptyMap(),
        mdName: String? = "${note.title}.md",
        mdId: String = note.id,
    ) {
        val inkDir = "$folder/_ink/${note.id}"
        dirs[""] = ((dirs[""] ?: emptyList()) + (folder to true)).distinct()
        dirs[folder] = ((dirs[folder] ?: emptyList()) + ("_ink" to true) + listOfNotNull(mdName?.let { it to false })).distinct()
        dirs["$folder/_ink"] = (dirs["$folder/_ink"] ?: emptyList()) + (note.id to true)
        dirs[inkDir] = listOf("ink.json" to false) + extra.keys.map { it to false }
        files["$inkDir/ink.json"] = NoteJson.encodeToString(Note.serializer(), note).toByteArray()
        extra.forEach { (rel, bytes) -> files["$inkDir/$rel"] = bytes }
        if (mdName != null) files["$folder/$mdName"] = "---\nadnote-id: $mdId\ntitle: ${note.title}\n---\n".toByteArray()
    }

    @Test
    fun testScanFindsNotesAndReportsLocalState() {
        val missing = Note(id = "aaaa", title = "远端独有", folder = "工作", pages = listOf(Page(width = 10, height = 10)), createdAt = 1L, updatedAt = 500L)
        val older = Note(id = "bbbb", title = "本地较旧", folder = "工作", pages = listOf(Page(width = 10, height = 10)), createdAt = 1L, updatedAt = 900L)
        putRemoteNote("工作", missing)
        putRemoteNote("工作", older)
        repository.save(older.copy(updatedAt = 100L))

        val found = engine.scan()
        assertEquals(2, found.size)
        assertEquals(LocalState.MISSING, found.first { it.note.id == "aaaa" }.localState)
        assertEquals(LocalState.OLDER, found.first { it.note.id == "bbbb" }.localState)
        assertEquals("工作/_ink/aaaa", found.first { it.note.id == "aaaa" }.inkDir)
    }

    @Test
    fun testRestoreWritesNoteAndAssets() {
        val note = Note(
            id = "cccc", title = "带录音", folder = "读书", pages = listOf(Page(width = 10, height = 10)),
            createdAt = 1L, updatedAt = 700L,
            recordings = listOf(Recording(id = "r1", path = "audio/r1.m4a", createdAt = 1L, durationMs = 10L)),
        )
        putRemoteNote("读书", note, extra = mapOf("audio/r1.m4a" to byteArrayOf(7, 7)))

        val result = engine.restore(engine.scan())
        assertEquals(1, result.restored)
        val local = repository.load("cccc")
        assertNotNull(local)
        assertEquals("带录音", local!!.title)
        assertEquals("读书/带录音.md", local.sync.remoteMdPath)
        assertEquals(700L, local.sync.lastSyncedAt)
        assertEquals(listOf("audio/r1.m4a"), local.sync.uploadedAssets)
        assertTrue(repository.assetFile("cccc", "audio/r1.m4a").readBytes().contentEquals(byteArrayOf(7, 7)))
    }

    @Test
    fun testRestoreLegacyInkJson() {
        // v0.1 格式：点是对象数组，没有 coordVersion / layers 等字段
        val legacy = """{"id":"dddd","title":"旧格式","folder":"收件箱","tags":[],"pages":[{"id":"p1","width":100,"height":100,
            |"strokes":[{"id":"s1","points":[{"x":1.0,"y":2.0,"pressure":0.5,"t":1},{"x":3.0,"y":4.0,"pressure":0.5,"t":2}],"width":3.0}]}],
            |"createdAt":1,"updatedAt":300}""".trimMargin().replace("\n", "")
        dirs[""] = listOf("收件箱" to true)
        dirs["收件箱"] = listOf("_ink" to true)
        dirs["收件箱/_ink"] = listOf("dddd" to true)
        dirs["收件箱/_ink/dddd"] = listOf("ink.json" to false)
        files["收件箱/_ink/dddd/ink.json"] = legacy.toByteArray()

        assertEquals(1, engine.restore(engine.scan()).restored)
        val local = repository.load("dddd")!!
        assertEquals(2, local.pages[0].strokes[0].points.size)
        assertEquals(0, local.coordVersion)
    }

    @Test
    fun testScanKeepsNewestCopyPerNoteId() {
        val old = Note(id = "eeee", title = "重复", folder = "旧", pages = listOf(Page(width = 10, height = 10)), createdAt = 1L, updatedAt = 300L)
        val new = old.copy(folder = "新", updatedAt = 800L)
        putRemoteNote("旧", old)
        putRemoteNote("新", new)

        val found = engine.scan()
        assertEquals(1, found.size)
        assertEquals(800L, found[0].note.updatedAt)
        assertEquals(1, engine.restore(found).restored)
        val local = repository.load("eeee")!!
        assertEquals(800L, local.updatedAt)
        assertEquals("新/重复.md", local.sync.remoteMdPath)
    }

    @Test
    fun testScanSkipsNoteWhoseIdDiffersFromDirectory() {
        val note = Note(id = "../x", title = "伪造", folder = "f", pages = listOf(Page(width = 10, height = 10)), createdAt = 1L, updatedAt = 1L)
        putRemoteNote("f", note.copy(id = "ffff"))
        files["f/_ink/ffff/ink.json"] = NoteJson.encodeToString(Note.serializer(), note).toByteArray()

        assertTrue(engine.scan().isEmpty())
    }

    private fun evilNote() = Note(
        id = "gggg", title = "越界", folder = "f", createdAt = 1L, updatedAt = 5L,
        pages = listOf(Page(width = 10, height = 10, images = listOf(com.adnote.model.ImageItem(path = "../../evil.jpg", x = 0f, y = 0f, width = 1f, height = 1f)))),
    )

    private fun assertNoEvilFile() {
        assertFalse(java.io.File(tempFolder.root, "evil.jpg").exists())
        assertFalse(java.io.File(tempFolder.root, "notes/evil.jpg").exists())
        assertFalse(java.io.File(tempFolder.root, "notes/gggg/evil.jpg").exists())
    }

    @Test
    fun testScanDropsNoteReferencingAssetOutsideNoteDirectory() {
        putRemoteNote("f", evilNote(), extra = mapOf("../../evil.jpg" to byteArrayOf(1)))

        val found = engine.scan()
        assertTrue(found.none { it.note.id == "gggg" })
        assertEquals(0, engine.restore(found).restored)
        assertNoEvilFile()
        assertNull(repository.load("gggg"))
    }

    @Test
    fun testRestoreRejectsAttachmentOutsideNoteDirectory() {
        // 绕过 scan 的检查，直接恢复：restoreOne 自己也要拦住越界附件
        putRemoteNote("f", evilNote(), extra = mapOf("../../evil.jpg" to byteArrayOf(1)))
        val info = RemoteNoteInfo(evilNote(), "f", "f/_ink/gggg", LocalState.MISSING)

        assertEquals(1, engine.restore(listOf(info)).restored)
        assertNoEvilFile()
        assertTrue(repository.load("gggg")!!.sync.uploadedAssets.isEmpty())
    }

    @Test
    fun testSameTitleNotesRestoreToTheirOwnMdPaths() {
        val first = Note(id = "aaaa1111", title = "Foo", folder = "f", pages = listOf(Page(width = 10, height = 10)), createdAt = 1L, updatedAt = 10L)
        val second = Note(id = "bbbb2222", title = "Foo", folder = "f", pages = listOf(Page(width = 10, height = 10)), createdAt = 2L, updatedAt = 20L)
        putRemoteNote("f", first, mdName = "Foo.md")
        putRemoteNote("f", second, mdName = "Foo (bbbb).md")

        assertEquals(2, engine.restore(engine.scan()).restored)
        assertEquals("f/Foo.md", repository.load("aaaa1111")!!.sync.remoteMdPath)
        assertEquals("f/Foo (bbbb).md", repository.load("bbbb2222")!!.sync.remoteMdPath)
    }

    @Test
    fun testRestoreLeavesMdPathNullWhenMdMissingOrBelongsToAnotherNote() {
        val noMd = Note(id = "hhhh", title = "无md", folder = "f", pages = listOf(Page(width = 10, height = 10)), createdAt = 1L, updatedAt = 10L)
        val foreign = Note(id = "iiii", title = "别人的", folder = "f", pages = listOf(Page(width = 10, height = 10)), createdAt = 2L, updatedAt = 20L)
        putRemoteNote("f", noMd, mdName = null)
        putRemoteNote("f", foreign, mdId = "zzzz")

        assertEquals(2, engine.restore(engine.scan()).restored)
        assertNull(repository.load("hhhh")!!.sync.remoteMdPath)
        assertNull(repository.load("iiii")!!.sync.remoteMdPath)
    }
}
