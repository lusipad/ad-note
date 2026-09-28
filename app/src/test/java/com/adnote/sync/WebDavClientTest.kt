package com.adnote.sync

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebDavClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: WebDavClient

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        client = WebDavClient(
            baseUrl = server.url("/").toString(),
            username = "testuser",
            password = "testpass",
            remoteRootDir = "AdNote"
        )
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun testPutAndGet() {
        // First request is MKCOL for "work" directory, second is PUT for "work/test.md"
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"tag123\""))
        val putResp = client.put("work/test.md", "# Hello")
        assertEquals(201, putResp.statusCode)
        assertEquals("\"tag123\"", putResp.etag)

        val recordedMkcol = server.takeRequest()
        assertEquals("MKCOL", recordedMkcol.method)
        assertTrue(recordedMkcol.path?.contains("/AdNote/work") == true)

        val recordedPut = server.takeRequest()
        assertEquals("PUT", recordedPut.method)
        assertTrue(recordedPut.path?.contains("/AdNote/work/test.md") == true)
        assertEquals("# Hello", recordedPut.body.readUtf8())
        assertNotNull(recordedPut.getHeader("Authorization"))

        // Test GET
        server.enqueue(MockResponse().setResponseCode(200).setBody("# Content").setHeader("ETag", "\"etag456\""))
        val getResp = client.get("work/test.md")
        assertNotNull(getResp)
        assertEquals("# Content", getResp?.body)
        assertEquals("\"etag456\"", getResp?.etag)

        // Test 404
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(client.get("notfound.md"))
    }

    @Test(expected = WebDavAuthException::class)
    fun testAuthFailureThrowsException() {
        server.enqueue(MockResponse().setResponseCode(401))
        client.get("test.md")
    }

    @Test
    fun testMove() {
        server.enqueue(MockResponse().setResponseCode(201))
        val moved = client.move("old.md", "new.md")
        assertTrue(moved)

        val req = server.takeRequest()
        assertEquals("MOVE", req.method)
        assertTrue(req.getHeader("Destination")?.contains("new.md") == true)
    }

    @Test
    fun testDelete() {
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(client.delete("old.md"))

        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
    }

    private fun multistatus(vararg entries: Triple<String, Boolean, String?>): String = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">""")
        for ((href, isDir, etag) in entries) {
            append("<D:response><D:href>").append(href).append("</D:href><D:propstat><D:prop>")
            append("<D:resourcetype>").append(if (isDir) "<D:collection/>" else "").append("</D:resourcetype>")
            if (etag != null) append("<D:getetag>").append(etag).append("</D:getetag>")
            append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
        }
        append("</D:multistatus>")
    }

    @Test
    fun testListDirectory() {
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                multistatus(
                    Triple("/AdNote/%E5%B7%A5%E4%BD%9C/", true, null),          // 目录自身，应被排除
                    Triple("/AdNote/%E5%B7%A5%E4%BD%9C/a.md", false, "\"e1\""),
                    Triple("/AdNote/%E5%B7%A5%E4%BD%9C/_ink", true, null),       // 目录，无末尾斜杠
                )
            )
        )
        val entries = client.list("工作")
        val req = server.takeRequest()
        assertEquals("PROPFIND", req.method)
        assertEquals("1", req.getHeader("Depth"))

        assertEquals(2, entries.size)
        val md = entries.first { !it.isDir }
        assertEquals("工作/a.md", md.path)
        assertEquals("a.md", md.name)
        assertEquals("\"e1\"", md.etag)
        val ink = entries.first { it.isDir }
        assertEquals("工作/_ink", ink.path)
        assertEquals("_ink", ink.name)
    }

    @Test
    fun testListHandlesAbsoluteHref() {
        val base = server.url("/").toString().trimEnd('/')
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                multistatus(
                    Triple("$base/AdNote/", true, null),
                    Triple("$base/AdNote/x.md", false, "\"e9\""),
                )
            )
        )
        val entries = client.list("")
        assertEquals(1, entries.size)
        assertEquals("x.md", entries[0].path)
    }

    @Test
    fun testListNotFoundReturnsEmpty() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(client.list("nope").isEmpty())
    }

    @Test
    fun testListEmptyBodyReturnsEmpty() {
        server.enqueue(MockResponse().setResponseCode(200))
        assertTrue(client.list("x").isEmpty())
    }

    @Test
    fun testPutFileSendsExactBytes() {
        val file = java.io.File.createTempFile("adnote", ".bin").apply {
            deleteOnExit()
            writeBytes(byteArrayOf(5, 6, 7, 8))
        }
        server.enqueue(MockResponse().setResponseCode(201)) // MKCOL AdNote/a
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"f1\""))
        val resp = client.putFile("a/b.bin", file, "application/octet-stream")
        assertEquals("\"f1\"", resp.etag)

        assertEquals("MKCOL", server.takeRequest().method)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("application/octet-stream", put.getHeader("Content-Type"))
        assertTrue(put.body.readByteArray().contentEquals(byteArrayOf(5, 6, 7, 8)))
    }

    @Test
    fun testDownloadWritesFileAtomically() {
        val dir = java.nio.file.Files.createTempDirectory("adnote-dl").toFile().apply { deleteOnExit() }
        val target = java.io.File(dir, "sub/out.bin")
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))
        assertTrue(client.download("a/b.bin", target))
        assertTrue(target.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertFalse(java.io.File(dir, "sub/out.bin.part").exists())

        server.enqueue(MockResponse().setResponseCode(404))
        val missing = java.io.File(dir, "sub/missing.bin")
        assertFalse(client.download("a/none.bin", missing))
        assertFalse(missing.exists())
    }
}
