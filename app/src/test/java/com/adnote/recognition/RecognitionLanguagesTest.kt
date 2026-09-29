package com.adnote.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionLanguagesTest {

    @Test
    fun defaultIsSimplifiedChinese() {
        assertEquals("zh-Hani-CN", RecognitionLanguages.DEFAULT)
        assertEquals("中文（简体）", RecognitionLanguages.byTag(null).displayName)
        assertEquals("中文（简体）", RecognitionLanguages.byTag("nope").displayName)
    }

    @Test
    fun knownTagsResolve() {
        assertEquals("英语", RecognitionLanguages.byTag("en-US").displayName)
        assertTrue(RecognitionLanguages.ALL.map { it.tag }.containsAll(listOf("zh-Hani-CN", "zh-Hani-TW", "en-US", "ja")))
    }
}
