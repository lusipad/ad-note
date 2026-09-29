package com.adnote.export

import com.adnote.model.Note
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 生成 / 合并与 Obsidian 兼容的 Markdown。
 *
 * 文件由三部分组成：
 * 1. front matter：AdNote 管理的键（adnote-id、title、tags、created、updated）会被重写，
 *    用户自己加的其他键原样保留；
 * 2. 用户区：生成区以外的正文，永不覆盖；
 * 3. 生成区：BEGIN/END 标记之间，每次同步整体替换。
 */
object MarkdownComposer {

    const val BEGIN = "<!-- adnote:begin 以下内容由 AdNote 自动生成，请勿编辑 -->"
    const val END = "<!-- adnote:end -->"
    private val OWNED_KEYS = setOf("adnote-id", "title", "tags", "created", "updated")

    data class Parsed(
        /** 非 AdNote 管理的 front matter 行（保持原样）。 */
        val foreignFrontMatter: List<String>,
        val tags: List<String>?,
        val before: String,
        val after: String,
        /** front matter 里的 adnote-id；没有则 null。 */
        val adnoteId: String?,
    )

    /**
     * @param existing 远端已有的 md 内容；null 表示新建
     * @param inkDir md 所在目录到笔迹目录的相对路径，如 "_ink/8f3c..."
     */
    fun compose(
        note: Note,
        inkDir: String,
        existing: String?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val parsed = existing?.let(::parse)
        val sb = StringBuilder()
        sb.append("---\n")
        sb.append("adnote-id: ").append(note.id).append('\n')
        sb.append("title: ").append(yamlScalar(note.title)).append('\n')
        if (note.tags.isEmpty()) {
            sb.append("tags: []\n")
        } else {
            sb.append("tags:\n")
            note.tags.forEach { sb.append("  - ").append(yamlScalar(it)).append('\n') }
        }
        sb.append("created: ").append(iso(note.createdAt, zone)).append('\n')
        sb.append("updated: ").append(iso(note.updatedAt, zone)).append('\n')
        parsed?.foreignFrontMatter?.forEach { sb.append(it).append('\n') }
        sb.append("---\n")

        val before = parsed?.before ?: (note.userMarkdown?.let { "$it\n\n" } ?: "\n")
        sb.append(before)
        if (!before.endsWith("\n")) sb.append('\n')
        if (!before.endsWith("\n\n")) sb.append('\n')
        sb.append(generated(note, inkDir))
        parsed?.after?.let { sb.append(it) }
        return sb.toString()
    }

    fun generated(note: Note, inkDir: String): String = buildString {
        append(BEGIN).append('\n')
        note.pages.forEachIndexed { i, page ->
            val n = i + 1
            append("## 第 ").append(n).append(" 页")
            page.bookmark?.let { append(" · ").append(it) }
            append("\n\n")
            append("![第 ").append(n).append(" 页](").append(inkDir).append('/').append(pageFileName(i)).append(")\n")
            val text = page.recognizedText?.trim()
            if (!text.isNullOrEmpty()) {
                append('\n')
                text.lines().forEach { append("> ").append(it).append('\n') }
            }
            val typed = page.texts.map { it.text.trim() }.filter { it.isNotEmpty() }
            if (typed.isNotEmpty()) {
                append('\n')
                typed.forEach { t -> append("- ").append(t.replace("\n", " ")).append('\n') }
            }
            append('\n')
        }
        if (note.recordings.isNotEmpty()) {
            append("## 录音\n\n")
            note.recordings.forEachIndexed { i, r ->
                val secs = r.durationMs / 1000
                append("![录音 ").append(i + 1).append(" · ")
                append("%d:%02d".format(secs / 60, secs % 60)).append("](")
                append(inkDir).append('/').append(r.path).append(")\n")
            }
            append('\n')
        }
        append(END).append('\n')
    }

    fun pageFileName(index: Int): String = "page-%03d.svg".format(index + 1)

    /** 用户区文本：生成区前后的正文合并，空则 null。 */
    fun userContent(parsed: Parsed): String? = buildString {
        append(parsed.before.trim())
        if (parsed.after.isNotBlank()) {
            if (isNotEmpty()) append("\n\n")
            append(parsed.after.trim())
        }
    }.trim().ifEmpty { null }

    fun parse(md: String): Parsed {
        val text = md.replace("\r\n", "\n")
        var body = text
        val foreign = ArrayList<String>()
        var tags: List<String>? = null
        var adnoteId: String? = null

        if (text.startsWith("---\n")) {
            val end = text.indexOf("\n---", 4)
            if (end >= 0) {
                val fmLines = text.substring(4, end).split('\n')
                val afterFm = text.indexOf('\n', end + 4).let { if (it < 0) text.length else it + 1 }
                body = text.substring(afterFm)
                var i = 0
                while (i < fmLines.size) {
                    val line = fmLines[i]
                    val key = line.substringBefore(':', "").trim()
                    val isTopLevel = line.isNotEmpty() && !line[0].isWhitespace() && !line.startsWith("-")
                    if (isTopLevel && key in OWNED_KEYS) {
                        // 收集该键下缩进的续行（如块状列表）
                        val block = ArrayList<String>()
                        var j = i + 1
                        while (j < fmLines.size && (fmLines[j].startsWith(" ") || fmLines[j].startsWith("-"))) {
                            block += fmLines[j]; j++
                        }
                        if (key == "tags") tags = parseTags(line.substringAfter(':').trim(), block)
                        if (key == "adnote-id") adnoteId = unquote(line.substringAfter(':').trim()).trim().ifEmpty { null }
                        i = j
                    } else {
                        if (line.isNotBlank()) foreign += line
                        i++
                    }
                }
            }
        }

        val b = body.indexOf(BEGIN)
        val e = body.indexOf(END)
        return if (b >= 0 && e > b) {
            val afterEnd = body.indexOf('\n', e).let { if (it < 0) body.length else it + 1 }
            Parsed(foreign, tags, body.substring(0, b), body.substring(afterEnd), adnoteId)
        } else {
            // 用户删掉了标记：整段正文都当作用户区，生成区追加在后面
            Parsed(foreign, tags, body, "", adnoteId)
        }
    }

    private fun parseTags(inline: String, block: List<String>): List<String> {
        val raw = when {
            inline.startsWith("[") -> inline.removePrefix("[").removeSuffix("]").split(',')
            inline.isNotEmpty() -> inline.split(',', ' ')
            else -> block.map { it.trim().removePrefix("-").trim() }
        }
        return raw.map { unquote(it.trim()).removePrefix("#") }.filter { it.isNotEmpty() }
    }

    private fun unquote(s: String): String =
        if (s.length >= 2 && (s.first() == '"' && s.last() == '"' || s.first() == '\'' && s.last() == '\'')) {
            s.substring(1, s.length - 1).replace("\\\"", "\"")
        } else s

    private fun yamlScalar(s: String): String {
        val needsQuote = s.isEmpty() || s.first().isWhitespace() || s.last().isWhitespace() ||
            s.any { it in ":#,[]{}&*!|>'\"%@`" } || s.first() == '-'
        return if (needsQuote) "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" else s
    }

    private fun iso(epochMs: Long, zone: ZoneId): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(epochMs).atZone(zone).withNano(0))
}
