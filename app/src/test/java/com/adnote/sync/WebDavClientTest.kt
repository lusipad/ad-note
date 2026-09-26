package com.adnote.sync

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
}
