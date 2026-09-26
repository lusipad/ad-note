package com.adnote.export

import com.adnote.model.Note
import com.adnote.model.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class MarkdownComposerTest {

    private val fixedZone = ZoneId.of("Asia/Shanghai")

    @Test
    fun testComposeNewNote() {
        val note = Note(
            id = "note01",
            title = "会议笔记",
            tags = listOf("工作", "敏捷"),
            pages = listOf(
                Page(
                    width = 1000,
                    height = 1500,
                    recognizedText = "第一页手写文本"
                )
            ),
            createdAt = 1710000000000L,
            updatedAt = 1710000000000L
        )

        val md = MarkdownComposer.compose(note, "_ink/note01", existing = null, zone = fixedZone)

        assertTrue(md.startsWith("---\nadnote-id: note01\ntitle: 会议笔记\n"))
        assertTrue(md.contains("tags:\n  - 工作\n  - 敏捷\n"))
        assertTrue(md.contains(MarkdownComposer.BEGIN))
        assertTrue(md.contains("![第 1 页](_ink/note01/page-001.svg)"))
        assertTrue(md.contains("> 第一页手写文本"))
        assertTrue(md.endsWith(MarkdownComposer.END + "\n"))
    }

    @Test
    fun testComposeMergeExisting() {
        val existing = """
---
adnote-id: note01
title: 旧标题
tags: [旧标签, 外部修改]
custom-key: my-value
another-key: 123
---

这是 Obsidian 用户自己写的核心思考与备注。
不应该被覆盖。

<!-- adnote:begin 以下内容由 AdNote 自动生成，请勿编辑 -->
## 第 1 页
![第 1 页](_ink/note01/page-001.svg)
> 旧识别文字
<!-- adnote:end -->

这是用户写在底部的追加内容。
""".trimIndent()

        val parsed = MarkdownComposer.parse(existing)
        assertEquals(listOf("旧标签", "外部修改"), parsed.tags)
        assertEquals(listOf("custom-key: my-value", "another-key: 123"), parsed.foreignFrontMatter)
        assertTrue(parsed.before.contains("这是 Obsidian 用户自己写的核心思考与备注。"))
        assertTrue(parsed.after.contains("这是用户写在底部的追加内容。"))

        val updatedNote = Note(
            id = "note01",
            title = "新标题",
            tags = listOf("新标签"),
            pages = listOf(
                Page(
                    width = 1000,
                    height = 1500,
                    recognizedText = "新识别文字"
                )
            ),
            createdAt = 1710000000000L,
            updatedAt = 1710003600000L
        )

        val merged = MarkdownComposer.compose(updatedNote, "_ink/note01", existing = existing, zone = fixedZone)

        // Front matter checks
        assertTrue(merged.contains("title: 新标题"))
        assertTrue(merged.contains("custom-key: my-value"))
        assertTrue(merged.contains("another-key: 123"))

        // User sections preserved
        assertTrue(merged.contains("这是 Obsidian 用户自己写的核心思考与备注。"))
        assertTrue(merged.contains("这是用户写在底部的追加内容。"))

        // Generated section replaced
        assertTrue(merged.contains("> 新识别文字"))
        assertTrue(!merged.contains("> 旧识别文字"))
    }
}
