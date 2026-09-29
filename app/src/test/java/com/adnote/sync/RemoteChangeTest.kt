package com.adnote.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteChangeTest {

    @Test
    fun sha256IsLowercaseHex() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ContentHash.sha256("abc"))
    }

    @Test
    fun etagOnBothSidesDecides() {
        assertFalse(RemoteChange.detect("\"a\"", "hash-x", "\"a\"", "changed body"))
        assertTrue(RemoteChange.detect("\"a\"", ContentHash.sha256("same"), "\"b\"", "same"))
    }

    @Test
    fun hashUsedWhenEtagMissing() {
        val body = "hello"
        assertFalse(RemoteChange.detect(null, ContentHash.sha256(body), null, body))
        assertTrue(RemoteChange.detect(null, ContentHash.sha256(body), null, "hello!"))
        // 服务器 PUT 不回 ETag、GET 才回：本地没记 ETag，靠哈希
        assertFalse(RemoteChange.detect(null, ContentHash.sha256(body), "\"srv\"", body))
    }

    @Test
    fun noLocalRecordMeansChanged() {
        assertTrue(RemoteChange.detect(null, null, "\"x\"", "anything"))
        assertTrue(RemoteChange.detect(null, null, null, "anything"))
    }
}
