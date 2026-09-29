package com.adnote.sync

import com.adnote.model.Note

/** 笔记引用的附件（相对笔记目录的路径），远端放在 _ink/<id>/ 下同名位置。 */
object NoteAssets {

    /** 顺序：录音 → 页面图片 → 页面背景 → PDF 原文；去重。 */
    fun referenced(note: Note): List<String> {
        val out = LinkedHashSet<String>()
        note.recordings.forEach { out += it.path }
        note.pages.forEach { p -> p.images.forEach { out += it.path } }
        note.pages.forEach { p -> p.backgroundImage?.let { out += it } }
        note.pdfPath?.let { out += it }
        return out.toList()
    }

    fun mimeType(relPath: String): String = when (relPath.substringAfterLast('.').lowercase()) {
        "m4a", "mp4" -> "audio/mp4"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    /** 上次同步已上传的附件路径；旧版本只记了录音 id，按当前录音列表换算成路径。 */
    fun previouslyUploaded(note: Note): Set<String> {
        val out = LinkedHashSet(note.sync.uploadedAssets)
        note.recordings.filter { it.id in note.sync.uploadedRecordings }.forEach { out += it.path }
        return out
    }
}
