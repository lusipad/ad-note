package com.adnote.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinLockTest {
    @Test
    fun hashAndVerify() {
        val h = PinLock.hash("2468")
        assertTrue(PinLock.verify("2468", h))
        assertFalse(PinLock.verify("1357", h))
        assertNotEquals(h, PinLock.hash("2468"))
        assertTrue(PinLock.verify("anything", null))
        assertFalse(PinLock.verify("2468", "garbage"))
    }

    @Test
    fun pinFormat() {
        assertTrue(PinLock.isValidPin("0000"))
        assertFalse(PinLock.isValidPin("12a4"))
        assertFalse(PinLock.isValidPin("123"))
    }
}
