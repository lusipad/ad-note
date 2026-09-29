package com.adnote.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class TagMergeTest {

    @Test
    fun bothSidesAdd() {
        val r = TagMerge.merge(base = listOf("a"), local = listOf("a", "b"), remote = listOf("a", "c"))
        assertEquals(listOf("a", "b", "c"), r)
    }

    @Test
    fun removalOnEitherSideWins() {
        assertEquals(listOf("b"), TagMerge.merge(base = listOf("a", "b"), local = listOf("b"), remote = listOf("a", "b")))
        assertEquals(listOf("a"), TagMerge.merge(base = listOf("a", "b"), local = listOf("a", "b"), remote = listOf("a")))
    }

    @Test
    fun emptyBaseIsUnion() {
        assertEquals(listOf("x", "y"), TagMerge.merge(base = emptyList(), local = listOf("x"), remote = listOf("y")))
    }

    @Test
    fun duplicatesCollapse() {
        assertEquals(listOf("a", "b"), TagMerge.merge(base = listOf("a"), local = listOf("a", "b"), remote = listOf("b", "a")))
    }
}
