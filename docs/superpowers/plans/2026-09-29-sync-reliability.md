# 同步可靠性与云端恢复 实施计划（子系统 A）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让远端 `_ink` 目录成为完整备份并可一键恢复；让 Obsidian 端的修改可靠地回到设备；修掉标签覆盖、ETag 误判、改名孤儿文件、锁定笔记搜索泄露。

**Architecture:** 所有新逻辑放在 `sync/`、`storage/`、`model/` 三个纯 JVM 包里，UI 只做薄薄一层调用。`WebDavClient` 新增 PROPFIND 列目录与二进制下载；`SyncEngine` 在现有「推送」流程里插入附件上传、三方标签合并、孤儿登记，并在推送之后新增「拉取」阶段；新类 `RestoreEngine` 负责扫描与恢复。

**Tech Stack:** Kotlin 2.0、kotlinx-serialization、OkHttp 4 + MockWebServer、JUnit 4、JDK `javax.xml.parsers`（解析 PROPFIND 响应）。

**Spec:** `docs/superpowers/specs/2026-09-29-functional-fixes.md` 第 3.1、3.2 节。

## Global Constraints

- 不新增第三方依赖；`sync`/`storage`/`model` 不引用 `android.*`。
- 新增 JSON 字段全部带默认值；`SyncState.uploadedRecordings` 保留可读，不再写入新数据。
- 已有 146 个测试继续通过；`testObsidianTagMerge` 需按 Task 3 说明调整前置条件。
- 每个任务结束时 `./gradlew testDebugUnitTest` 必须全绿再提交。
- 测试文件与源码同包：`app/src/test/java/com/adnote/<pkg>/XxxTest.kt`。
- 在 Git Bash 里用 `./gradlew … --offline` 运行 Gradle（无新依赖，离线可构建）；Bash 工具的超时要设到 600000 毫秒，首次编译较慢。
- 附件（PDF、图片、录音）一律流式读写，不得把整个文件读进内存：PDF 可能几十 MB，Android 堆上限会 OOM。

## Review Focus

1. **远端 href 是完整 URL 而不是路径**（部分 WebDAV 服务如 Apache mod_dav 返回 `http://host/dav/...`）：`WebDavClient.list` 必须仍能算出相对路径。Task 1 的 `testListHandlesAbsoluteHref` 覆盖。
2. **PROPFIND 响应 href 末尾有无斜杠不一致**：目录 `/AdNote/工作/` 与 `/AdNote/工作` 都要识别为目录。Task 1 的 `testListDirectory` 同时包含两种写法。
3. **ink.json 是旧格式**（无 `coordVersion`、点列表是对象数组）：恢复后要能打开。Task 7 的 `testRestoreLegacyInkJson` 覆盖。
4. **同步期间用户又改了标签**：拉取阶段不能覆盖本地脏笔记。Task 6 的 `testPullSkipsLocallyDirtyNote` 覆盖。
5. **附件文件在本地已经不存在**（用户手动清过缓存）：上传时跳过而不是整篇失败。Task 5 的 `testMissingAssetIsSkipped` 覆盖。
6. **PROPFIND 返回 200 但正文为空**（部分代理或旧服务）：当作空目录，不抛异常，也不往标准错误打印 `[Fatal Error]`。Task 1 的 `testListEmptyBodyReturnsEmpty` 覆盖。
7. **PDF 原文几十 MB**：上传下载必须流式，`putFile` 直接发文件，`download` 写临时文件后替换。Task 1 的 `testPutFileSendsExactBytes` 与 `testDownloadWritesFileAtomically` 覆盖行为，代码里不得对附件调用 `readBytes()` 或 `bytes()`。

---

### Task 1: WebDavClient 新增 PROPFIND 列目录、流式上传与下载

**Files:**
- Modify: `app/src/main/java/com/adnote/sync/WebDavClient.kt`
- Test: `app/src/test/java/com/adnote/sync/WebDavClientTest.kt`

**Interfaces:**
- Produces:
  - `data class DavEntry(val path: String, val name: String, val isDir: Boolean, val etag: String?)`，`path` 相对远端根目录、不带首尾斜杠。
  - `fun WebDavClient.list(dirPath: String): List<DavEntry>`，不包含目录自身；404 或正文为空返回空列表；401 抛 `WebDavAuthException`。
  - `fun WebDavClient.putFile(relativePath: String, file: java.io.File, contentType: String): WebDavResponse`，流式上传本地文件。
  - `fun WebDavClient.download(relativePath: String, target: java.io.File): Boolean`，流式下载到 `target`（先写 `.part` 临时文件再替换），远端 404 返回 false。

- [ ] **Step 1: 写失败测试**

在 `WebDavClientTest.kt` 顶部 import 区补 `import org.junit.Assert.assertFalse`，然后在类内末尾追加：

```kotlin
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
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew testDebugUnitTest --tests "com.adnote.sync.WebDavClientTest" -q`
Expected: 编译失败，提示 `list`、`putFile`、`download`、`DavEntry` 未定义。

- [ ] **Step 3: 实现**

先在 `WebDavClient.kt` 顶部 import 区加入 `import okhttp3.RequestBody.Companion.asRequestBody`。然后在 `WebDavResponse` 声明之后添加：

```kotlin
/** PROPFIND 列出的一个条目。[path] 相对远端根目录，不带首尾斜杠。 */
data class DavEntry(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val etag: String?,
)
```

在 `WebDavClient` 类内 `move` 函数之后添加：

```kotlin
    /** 远端根目录在服务器上的绝对路径（已解码），用于把 PROPFIND 的 href 换算成相对路径。 */
    private val rootPath: String by lazy { resolveUrl("").toUri().path.trimEnd('/') }

    /**
     * 列出目录的直接子项（Depth: 1），不含目录自身。目录不存在返回空列表。
     * href 可能是绝对路径也可能是完整 URL，统一取 path 部分再去掉根目录前缀。
     */
    fun list(dirPath: String): List<DavEntry> {
        val body = """<?xml version="1.0" encoding="utf-8"?>""" +
            """<D:propfind xmlns:D="DAV:"><D:prop><D:resourcetype/><D:getetag/></D:prop></D:propfind>"""
        val request = newRequestBuilder(resolveUrl(dirPath))
            .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "1")
            .build()
        val self = dirPath.trim('/')
        return client.newCall(request).execute().use { response ->
            if (response.code == 401) throw WebDavAuthException()
            if (response.code == 404) return emptyList()
            if (!response.isSuccessful && response.code != 207) {
                throw WebDavException("PROPFIND 失败 (${response.code}): $dirPath", response.code)
            }
            parseMultistatus(response.body?.string().orEmpty()).filter { it.path != self }
        }
    }

    private fun parseMultistatus(xml: String): List<DavEntry> {
        if (xml.isBlank()) return emptyList()
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // 不接受 DOCTYPE，避免解析外部实体。Android 自带的解析器不认这个特性，忽略即可。
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        // 默认错误处理器会往标准错误打印 "[Fatal Error]"；DefaultHandler 只抛异常，不打印
        val builder = factory.newDocumentBuilder().apply { setErrorHandler(org.xml.sax.helpers.DefaultHandler()) }
        val doc = builder.parse(xml.byteInputStream())
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        val out = ArrayList<DavEntry>()
        for (i in 0 until responses.length) {
            val r = responses.item(i) as org.w3c.dom.Element
            val href = r.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent?.trim() ?: continue
            val decoded = runCatching { java.net.URI(href).path }.getOrNull() ?: href
            if (!decoded.startsWith(rootPath)) continue
            val rel = decoded.removePrefix(rootPath).trim('/')
            val isDir = r.getElementsByTagNameNS("DAV:", "collection").length > 0
            val etag = r.getElementsByTagNameNS("DAV:", "getetag").item(0)?.textContent?.trim()?.ifEmpty { null }
            out += DavEntry(path = rel, name = rel.substringAfterLast('/'), isDir = isDir, etag = etag)
        }
        return out
    }

    /** 流式上传本地文件，不把整个文件读进内存（PDF 可能有几十 MB）。 */
    fun putFile(relativePath: String, file: java.io.File, contentType: String): WebDavResponse {
        ensureParentDirs(relativePath)
        val request = newRequestBuilder(resolveUrl(relativePath))
            .put(file.asRequestBody(contentType.toMediaType()))
            .build()
        return client.newCall(request).execute().use { response ->
            if (response.code == 401) throw WebDavAuthException()
            if (!response.isSuccessful && response.code != 201 && response.code != 204) {
                throw WebDavException("PUT 失败 (${response.code}): $relativePath", response.code)
            }
            WebDavResponse(statusCode = response.code, etag = response.header("ETag"), body = null)
        }
    }

    /**
     * 流式下载到本地文件：先写 `.part` 临时文件，写完再替换目标，中途失败不会留下半截文件。
     * 远端不存在返回 false。
     */
    fun download(relativePath: String, target: java.io.File): Boolean {
        val request = newRequestBuilder(resolveUrl(relativePath)).get().build()
        return client.newCall(request).execute().use { response ->
            if (response.code == 401) throw WebDavAuthException()
            if (response.code == 404) return false
            if (!response.isSuccessful) {
                throw WebDavException("GET 失败 (${response.code}): $relativePath", response.code)
            }
            val body = response.body ?: return false
            target.parentFile?.mkdirs()
            val part = java.io.File(target.parentFile, target.name + ".part")
            try {
                body.byteStream().use { input -> part.outputStream().use { out -> input.copyTo(out) } }
                if (!part.renameTo(target)) {
                    target.delete()
                    check(part.renameTo(target)) { "无法写入 ${target.path}" }
                }
            } finally {
                part.delete()
            }
            true
        }
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew testDebugUnitTest --tests "com.adnote.sync.WebDavClientTest" -q`
Expected: PASS（含原有 3 个测试）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/adnote/sync/WebDavClient.kt app/src/test/java/com/adnote/sync/WebDavClientTest.kt
git commit -m "feat(sync): WebDAV PROPFIND directory listing and binary download"
```

---

### Task 2: SyncState 扩展字段 + 内容哈希 + 远端变化判定

**Files:**
- Modify: `app/src/main/java/com/adnote/model/Note.kt`（`SyncState`）
- Create: `app/src/main/java/com/adnote/sync/ContentHash.kt`
- Create: `app/src/main/java/com/adnote/sync/RemoteChange.kt`
- Test: `app/src/test/java/com/adnote/sync/RemoteChangeTest.kt`

**Interfaces:**
- Produces:
  - `SyncState.remoteMdHash: String?`、`SyncState.syncedTags: List<String>`、`SyncState.uploadedAssets: List<String>`
  - `object ContentHash { fun sha256(s: String): String }`（小写十六进制）
  - `object RemoteChange { fun detect(localEtag: String?, localHash: String?, remoteEtag: String?, remoteBody: String): Boolean }`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/adnote/sync/RemoteChangeTest.kt`：

```kotlin
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
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew testDebugUnitTest --tests "com.adnote.sync.RemoteChangeTest" -q`
Expected: 编译失败，`ContentHash`、`RemoteChange` 未定义。

- [ ] **Step 3: 实现**

`Note.kt` 中 `SyncState` 整体替换为：

```kotlin
@Serializable
data class SyncState(
    val lastSyncedAt: Long = 0L,
    /** 上次同步时 md 文件的远端路径（相对远端根目录），用于检测改名。 */
    val remoteMdPath: String? = null,
    /** 上次同步后远端 md 的 ETag，用于判断是否在别处被修改过。 */
    val remoteMdEtag: String? = null,
    /** 上次上传的 md 内容的 SHA-256；服务器不给 ETag 时靠它判断远端是否改过。 */
    val remoteMdHash: String? = null,
    /** 上次同步后双方一致的标签，三方合并的基准。 */
    val syncedTags: List<String> = emptyList(),
    /** 上次同步上传的页面 SVG 数量，删页后用于清理远端多余的 page-NNN.svg。 */
    val remotePageCount: Int = 0,
    /** 已上传到 _ink/<id>/ 的附件相对路径（录音、图片、背景、PDF）。 */
    val uploadedAssets: List<String> = emptyList(),
    /** 旧版本记录的已上传录音 id，只读兼容；新版本改用 [uploadedAssets]。 */
    val uploadedRecordings: List<String> = emptyList(),
)
```

创建 `app/src/main/java/com/adnote/sync/ContentHash.kt`：

```kotlin
package com.adnote.sync

import java.security.MessageDigest

object ContentHash {
    fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
```

创建 `app/src/main/java/com/adnote/sync/RemoteChange.kt`：

```kotlin
package com.adnote.sync

/**
 * 判断远端 md 自上次同步后是否被改过。
 * 两边都有 ETag 时以 ETag 为准；否则比较内容哈希；本地什么都没记录（首次连接）视为改过，
 * 让调用方走合并流程而不是直接覆盖。
 */
object RemoteChange {
    fun detect(localEtag: String?, localHash: String?, remoteEtag: String?, remoteBody: String): Boolean {
        if (localEtag != null && remoteEtag != null) return localEtag != remoteEtag
        if (localHash != null) return ContentHash.sha256(remoteBody) != localHash
        return true
    }
}
```

- [ ] **Step 4: 运行全部测试确认通过**

Run: `./gradlew testDebugUnitTest -q`
Expected: PASS（`SyncState` 新字段有默认值，旧测试与旧 JSON 不受影响）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/adnote/model/Note.kt app/src/main/java/com/adnote/sync/ContentHash.kt app/src/main/java/com/adnote/sync/RemoteChange.kt app/src/test/java/com/adnote/sync/RemoteChangeTest.kt
git commit -m "feat(sync): content hash fallback for remote change detection, extend SyncState"
```

---

### Task 3: 标签三方合并并接入推送流程

**Files:**
- Create: `app/src/main/java/com/adnote/sync/TagMerge.kt`
- Modify: `app/src/main/java/com/adnote/export/MarkdownComposer.kt`（新增 `userContent`）
- Modify: `app/src/main/java/com/adnote/sync/SyncEngine.kt:107-140`（`syncSingleNote` 第 2 步与第 6 步）
- Test: `app/src/test/java/com/adnote/sync/TagMergeTest.kt`
- Test: `app/src/test/java/com/adnote/sync/SyncEngineTest.kt`（调整 `testObsidianTagMerge`，新增 `testTagMergeKeepsLocalAdditions`）

**Interfaces:**
- Consumes: `RemoteChange.detect`、`ContentHash.sha256`、`SyncState.syncedTags/remoteMdHash`（Task 2）
- Produces:
  - `object TagMerge { fun merge(base: List<String>, local: List<String>, remote: List<String>): List<String> }`
  - `fun MarkdownComposer.userContent(parsed: Parsed): String?`（用户区 before+after 合并后的文本，空则 null）

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/adnote/sync/TagMergeTest.kt`：

```kotlin
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
```

在 `SyncEngineTest.kt` 里把 `testObsidianTagMerge` 的 `SyncState(...)` 改为（增加 `syncedTags`，表示「原始本地标签」是上次双方一致的、远端已把它删掉）：

```kotlin
            sync = SyncState(
                lastSyncedAt = 1000L,
                remoteMdPath = "收件箱/旧笔记.md",
                remoteMdEtag = "\"etag-initial-on-device\"", // Different ETag!
                syncedTags = listOf("原始本地标签"),
            )
```

并在同文件追加新测试：

```kotlin
    @Test
    fun testTagMergeKeepsLocalAdditions() {
        val remoteMd = """
---
adnote-id: note777
title: 合并
tags: [共同, 远端新增]
---

<!-- adnote:begin 以下内容由 AdNote 自动生成，请勿编辑 -->
<!-- adnote:end -->
""".trimIndent()
        var putBody: String? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.method) {
                "MKCOL" -> MockResponse().setResponseCode(201)
                "GET" -> MockResponse().setResponseCode(200).setBody(remoteMd).setHeader("ETag", "\"pc\"")
                "PUT" -> {
                    if (request.path.orEmpty().endsWith(".md")) putBody = request.body.readUtf8()
                    MockResponse().setResponseCode(201).setHeader("ETag", "\"after\"")
                }
                else -> MockResponse().setResponseCode(200)
            }
        }
        val note = com.adnote.model.Note(
            id = "note777", title = "合并", folder = "收件箱",
            tags = listOf("共同", "本地新增"),
            pages = listOf(com.adnote.model.Page(width = 10, height = 10)),
            createdAt = 1L, updatedAt = 2000L,
            sync = SyncState(lastSyncedAt = 1000L, remoteMdPath = "收件箱/合并.md", remoteMdEtag = "\"dev\"", syncedTags = listOf("共同")),
        )
        repository.save(note)

        syncEngine.sync()

        val synced = repository.load("note777")!!
        assertEquals(listOf("共同", "本地新增", "远端新增"), synced.tags)
        assertEquals(listOf("共同", "本地新增", "远端新增"), synced.sync.syncedTags)
        assertNotNull(synced.sync.remoteMdHash)
        assertTrue(putBody!!.contains("  - 本地新增"))
        assertTrue(putBody!!.contains("  - 远端新增"))
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew testDebugUnitTest --tests "com.adnote.sync.*" -q`
Expected: `TagMergeTest` 编译失败；`testTagMergeKeepsLocalAdditions` 失败（本地新增被远端覆盖）。

- [ ] **Step 3: 实现**

创建 `app/src/main/java/com/adnote/sync/TagMerge.kt`：

```kotlin
package com.adnote.sync

/**
 * 标签三方合并。base 是上次同步后双方一致的标签：
 * 任一方从 base 里删掉的标签不再保留，任一方新增的标签都保留。
 * 顺序：base 中保留的 → 本地新增 → 远端新增。
 */
object TagMerge {
    fun merge(base: List<String>, local: List<String>, remote: List<String>): List<String> {
        val baseSet = base.toSet()
        val removedLocal = baseSet - local.toSet()
        val removedRemote = baseSet - remote.toSet()
        val result = LinkedHashSet<String>()
        base.filter { it !in removedLocal && it !in removedRemote }.forEach { result += it }
        local.filter { it !in baseSet }.forEach { result += it }
        remote.filter { it !in baseSet }.forEach { result += it }
        return result.toList()
    }
}
```

`MarkdownComposer.kt` 在 `parse` 函数之前添加：

```kotlin
    /** 用户区文本：生成区前后的正文合并，空则 null。 */
    fun userContent(parsed: Parsed): String? = buildString {
        append(parsed.before.trim())
        if (parsed.after.isNotBlank()) {
            if (isNotEmpty()) append("\n\n")
            append(parsed.after.trim())
        }
    }.trim().ifEmpty { null }
```

`SyncEngine.syncSingleNote` 中，把「// 2. 获取远端已有的 Markdown」到 `val noteWithMerged = ...` 这一段整体替换为：

```kotlin
        // 2. 获取远端已有的 Markdown，判断 Obsidian 端是否改过，改过则三方合并标签、采用远端正文
        val existingResp = webDavClient.get(targetMdPath)
        var mergedTags = originalNote.tags
        var mergedUserMarkdown = originalNote.userMarkdown

        if (existingResp != null) {
            val body = existingResp.body.orEmpty()
            val parsed = MarkdownComposer.parse(body)
            val remoteUserContent = MarkdownComposer.userContent(parsed)
            val remoteChanged = RemoteChange.detect(
                localEtag = originalNote.sync.remoteMdEtag,
                localHash = originalNote.sync.remoteMdHash,
                remoteEtag = existingResp.etag,
                remoteBody = body,
            )
            if (remoteChanged) {
                mergedTags = TagMerge.merge(
                    base = originalNote.sync.syncedTags,
                    local = originalNote.tags,
                    remote = parsed.tags ?: emptyList(),
                )
                if (remoteUserContent != null) mergedUserMarkdown = remoteUserContent
            } else if (mergedUserMarkdown == null && remoteUserContent != null) {
                mergedUserMarkdown = remoteUserContent
            }
        }

        val noteWithMerged = originalNote.copy(tags = mergedTags, userMarkdown = mergedUserMarkdown)
```

「// 6. 更新本地同步状态」处的 `SyncState(...)` 改为：

```kotlin
        val syncState = SyncState(
            lastSyncedAt = System.currentTimeMillis(),
            remoteMdPath = targetMdPath,
            remoteMdEtag = putMdResp.etag ?: existingResp?.etag,
            remoteMdHash = ContentHash.sha256(mdContent),
            syncedTags = noteWithMerged.tags,
            remotePageCount = noteWithMerged.pages.size,
            uploadedRecordings = uploaded.filter { id -> noteWithMerged.recordings.any { it.id == id } },
        )
```

（`uploadedRecordings` 这一行在 Task 5 会被替换掉，这里先保留让测试通过。）

- [ ] **Step 4: 运行全部测试确认通过**

Run: `./gradlew testDebugUnitTest -q`
Expected: PASS。若 `testObsidianTagMerge` 仍失败，检查 Step 1 里 `syncedTags` 是否已加上。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/adnote/sync/TagMerge.kt app/src/main/java/com/adnote/sync/SyncEngine.kt app/src/main/java/com/adnote/export/MarkdownComposer.kt app/src/test/java/com/adnote/sync/TagMergeTest.kt app/src/test/java/com/adnote/sync/SyncEngineTest.kt
git commit -m "feat(sync): three-way tag merge instead of remote overwrite"
```

---

### Task 4: MOVE 失败时登记远端清理，避免孤儿文件

**Files:**
- Modify: `app/src/main/java/com/adnote/storage/NoteRepository.kt`（新增 `addRemoteCleanup`）
- Modify: `app/src/main/java/com/adnote/sync/SyncEngine.kt`（`sync` 抽出 `processTombstones`；`syncSingleNote` 第 1 步）
- Test: `app/src/test/java/com/adnote/storage/NoteRepositoryTest.kt`
- Test: `app/src/test/java/com/adnote/sync/SyncEngineTest.kt`

**Interfaces:**
- Produces:
  - `fun NoteRepository.addRemoteCleanup(noteId: String, remoteMdPath: String?, remoteInkDir: String?)`：写一条键为 `"$noteId-cleanup-<nanoTime>"` 的 `Tombstone`。
  - `SyncEngine` 内部 `private fun processTombstones(): String?`（返回认证错误信息或 null），在 `sync()` 开头和结尾各调用一次。
  - `syncSingleNote` 内的局部变量 `movedInk: Boolean`（无需 MOVE 时为 true），Task 5 会用它决定是否重传附件。

- [ ] **Step 1: 写失败测试**

`NoteRepositoryTest.kt` 追加：

```kotlin
    @Test
    fun testAddRemoteCleanupCreatesTombstoneWithoutTouchingNote() {
        val repo = NoteRepository(tempFolder.root)
        val note = repo.create("改名", "工作", 100, 100)
        repo.addRemoteCleanup(note.id, "工作/旧名.md", "工作/_ink/${note.id}")

        val tombs = repo.tombstones()
        assertEquals(1, tombs.size)
        assertTrue(tombs[0].noteId.startsWith("${note.id}-cleanup-"))
        assertEquals("工作/旧名.md", tombs[0].remoteMdPath)
        assertNotNull(repo.load(note.id))

        repo.clearTombstone(tombs[0].noteId)
        assertTrue(repo.tombstones().isEmpty())
    }
```

`SyncEngineTest.kt` 追加：

```kotlin
    @Test
    fun testFailedMoveSchedulesRemoteDelete() {
        val deleted = java.util.concurrent.CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = java.net.URLDecoder.decode(request.path.orEmpty(), "UTF-8")
                return when (request.method) {
                    "MKCOL" -> MockResponse().setResponseCode(201)
                    "MOVE" -> MockResponse().setResponseCode(500)
                    "GET" -> MockResponse().setResponseCode(404)
                    "PUT" -> MockResponse().setResponseCode(201).setHeader("ETag", "\"e\"")
                    "DELETE" -> { deleted += path; MockResponse().setResponseCode(204) }
                    else -> MockResponse().setResponseCode(200)
                }
            }
        }
        val note = com.adnote.model.Note(
            id = "mv1", title = "新名", folder = "工作",
            pages = listOf(com.adnote.model.Page(width = 10, height = 10)),
            createdAt = 1L, updatedAt = 2000L,
            sync = SyncState(lastSyncedAt = 1000L, remoteMdPath = "工作/旧名.md", remoteMdEtag = "\"x\""),
        )
        repository.save(note)

        val result = syncEngine.sync()
        assertEquals(1, result.success)
        // 同步结束前就尝试清理旧路径
        assertTrue(deleted.any { it.endsWith("/AdNote/工作/旧名.md") })
        assertTrue(repository.tombstones().isEmpty())
        assertEquals("工作/新名.md", repository.load("mv1")!!.sync.remoteMdPath)
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew testDebugUnitTest --tests "com.adnote.storage.NoteRepositoryTest" --tests "com.adnote.sync.SyncEngineTest" -q`
Expected: `addRemoteCleanup` 未定义；`testFailedMoveSchedulesRemoteDelete` 断言 `deleted` 失败。

- [ ] **Step 3: 实现**

`NoteRepository.kt` 在 `clearTombstone` 之后添加：

```kotlin
    /**
     * 登记一次远端清理：改名或移动时 MOVE 失败，旧路径的文件留在远端，
     * 由同步流程当作墓碑删除。键带时间戳，不与笔记本身的墓碑冲突。
     */
    fun addRemoteCleanup(noteId: String, remoteMdPath: String?, remoteInkDir: String?) {
        if (remoteMdPath == null && remoteInkDir == null) return
        val key = "$noteId-cleanup-${System.nanoTime()}"
        atomicWrite(File(tombDir, "$key.json"), NoteJson.encodeToString(Tombstone.serializer(), Tombstone(key, remoteMdPath, remoteInkDir)))
    }
```

`SyncEngine.sync` 开头的墓碑 for 循环整体替换为：

```kotlin
        // 1. 处理已删除笔记与改名残留的墓碑
        processTombstones()?.let { return SyncResult(total = 0, success = 0, failed = 1, firstError = it) }
```

在 `sync()` 的最终 `return SyncResult(...)` 之前加：

```kotlin
        // 本次同步中登记的改名残留，顺手清掉
        processTombstones()
```

在类中添加：

```kotlin
    /** 逐条删除墓碑对应的远端文件；认证失败返回错误信息，其余失败留待下次。 */
    private fun processTombstones(): String? {
        for (tombstone in repository.tombstones()) {
            try {
                tombstone.remoteMdPath?.let { webDavClient.delete(it) }
                tombstone.remoteInkDir?.let { webDavClient.delete(it) }
                repository.clearTombstone(tombstone.noteId)
            } catch (e: WebDavAuthException) {
                return e.message
            } catch (_: Exception) {
                // 删除失败留待下次同步继续尝试
            }
        }
        return null
    }
```

`syncSingleNote` 的「// 1. 标题或文件夹发生变化，尝试 MOVE」段替换为：

```kotlin
        // 1. 标题或文件夹发生变化，尝试 MOVE；失败则登记旧路径待删除
        var movedInk = true
        if (oldMdPath != null && oldMdPath != targetMdPath) {
            val movedMd = runCatching { webDavClient.move(oldMdPath, targetMdPath) }.getOrDefault(false)
            if (!movedMd) repository.addRemoteCleanup(originalNote.id, oldMdPath, null)
            if (oldInkDir != null && oldInkDir != targetInkDir) {
                movedInk = runCatching { webDavClient.move(oldInkDir, targetInkDir) }.getOrDefault(false)
                if (!movedInk) repository.addRemoteCleanup(originalNote.id, null, oldInkDir)
            }
        }
```

- [ ] **Step 4: 运行全部测试确认通过**

Run: `./gradlew testDebugUnitTest -q`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/adnote/storage/NoteRepository.kt app/src/main/java/com/adnote/sync/SyncEngine.kt app/src/test/java/com/adnote/storage/NoteRepositoryTest.kt app/src/test/java/com/adnote/sync/SyncEngineTest.kt
git commit -m "fix(sync): clean up remote files left behind when MOVE fails"
```

---

### Task 5: 附件统一上传与远端清理（图片、背景、PDF、录音）

**Files:**
- Create: `app/src/main/java/com/adnote/sync/NoteAssets.kt`
- Modify: `app/src/main/java/com/adnote/sync/SyncEngine.kt`（`syncSingleNote` 录音段与第 6 步）
- Test: `app/src/test/java/com/adnote/sync/NoteAssetsTest.kt`
- Test: `app/src/test/java/com/adnote/sync/SyncEngineTest.kt`

**Interfaces:**
- Consumes: `movedInk`（Task 4）、`SyncState.uploadedAssets`（Task 2）、`WebDavClient.putFile`（Task 1）
- Produces:
  - `object NoteAssets { fun referenced(note: Note): List<String>; fun mimeType(relPath: String): String; fun previouslyUploaded(note: Note): Set<String> }`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/adnote/sync/NoteAssetsTest.kt`：

```kotlin
package com.adnote.sync

import com.adnote.model.ImageItem
import com.adnote.model.Note
import com.adnote.model.Page
import com.adnote.model.Recording
import com.adnote.model.SyncState
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteAssetsTest {

    private fun note() = Note(
        title = "t", folder = "f",
        pages = listOf(
            Page(width = 10, height = 10, images = listOf(ImageItem(path = "images/a.jpg", x = 0f, y = 0f, width = 1f, height = 1f)), backgroundImage = "backgrounds/bg.png"),
            Page(width = 10, height = 10, images = listOf(ImageItem(path = "images/a.jpg", x = 0f, y = 0f, width = 1f, height = 1f))),
        ),
        createdAt = 0L, updatedAt = 0L,
        pdfPath = "document.pdf",
        recordings = listOf(Recording(id = "r1", path = "audio/r1.m4a", createdAt = 0L, durationMs = 1L)),
        sync = SyncState(uploadedRecordings = listOf("r1"), uploadedAssets = listOf("images/old.jpg")),
    )

    @Test
    fun referencedIsDistinctAndOrdered() {
        assertEquals(listOf("audio/r1.m4a", "images/a.jpg", "backgrounds/bg.png", "document.pdf"), NoteAssets.referenced(note()))
    }

    @Test
    fun mimeTypes() {
        assertEquals("audio/mp4", NoteAssets.mimeType("audio/x.m4a"))
        assertEquals("image/jpeg", NoteAssets.mimeType("images/x.jpg"))
        assertEquals("image/png", NoteAssets.mimeType("b.PNG"))
        assertEquals("application/pdf", NoteAssets.mimeType("document.pdf"))
        assertEquals("application/octet-stream", NoteAssets.mimeType("x.bin"))
    }

    @Test
    fun previouslyUploadedMergesLegacyRecordingIds() {
        assertEquals(setOf("images/old.jpg", "audio/r1.m4a"), NoteAssets.previouslyUploaded(note()))
    }
}
```

同时修改现有测试 `testRecordingsUploadOnce`（新版本不再写 `uploadedRecordings`）：把
`assertEquals(listOf("r1"), repository.load(created.id)!!.sync.uploadedRecordings)` 改为
`assertEquals(listOf("audio/r1.m4a"), repository.load(created.id)!!.sync.uploadedAssets)`。

`SyncEngineTest.kt` 追加（放在 `testRecordingsUploadOnce` 之后）：

```kotlin
    /** 记录 PUT/DELETE 路径的 dispatcher，GET 一律 404。 */
    private fun assetDispatcher(puts: MutableList<String>, deletes: MutableList<String>) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = java.net.URLDecoder.decode(request.path.orEmpty(), "UTF-8")
            return when (request.method) {
                "MKCOL" -> MockResponse().setResponseCode(201)
                "GET" -> MockResponse().setResponseCode(404)
                "PUT" -> { puts += path; MockResponse().setResponseCode(201).setHeader("ETag", "\"e\"") }
                "DELETE" -> { deletes += path; MockResponse().setResponseCode(204) }
                else -> MockResponse().setResponseCode(200)
            }
        }
    }

    @Test
    fun testImagesAndPdfUploadedOnceAndStaleAssetsDeleted() {
        val puts = java.util.concurrent.CopyOnWriteArrayList<String>()
        val deletes = java.util.concurrent.CopyOnWriteArrayList<String>()
        server.dispatcher = assetDispatcher(puts, deletes)

        val note = repository.create("附件", "f", 10, 10)
        val img = com.adnote.model.ImageItem(path = "images/p.jpg", x = 0f, y = 0f, width = 1f, height = 1f)
        repository.assetFile(note.id, "images/p.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(9)) }
        repository.assetFile(note.id, "document.pdf").writeBytes(byteArrayOf(1))
        repository.save(note.copy(pages = listOf(note.pages[0].copy(images = listOf(img))), pdfPath = "document.pdf"))

        syncEngine.sync()
        assertTrue(puts.any { it.endsWith("/_ink/${note.id}/images/p.jpg") })
        assertTrue(puts.any { it.endsWith("/_ink/${note.id}/document.pdf") })
        assertEquals(setOf("images/p.jpg", "document.pdf"), repository.load(note.id)!!.sync.uploadedAssets.toSet())

        // 删掉图片再同步：不重传 PDF，远端图片被删除
        puts.clear()
        val loaded = repository.load(note.id)!!
        repository.save(loaded.copy(pages = listOf(loaded.pages[0].copy(images = emptyList())), updatedAt = maxOf(loaded.updatedAt, loaded.sync.lastSyncedAt) + 1000))
        syncEngine.sync()
        assertFalse(puts.any { it.endsWith("/document.pdf") })
        assertTrue(deletes.any { it.endsWith("/_ink/${note.id}/images/p.jpg") })
        assertEquals(listOf("document.pdf"), repository.load(note.id)!!.sync.uploadedAssets)
    }

    @Test
    fun testMissingAssetIsSkipped() {
        val puts = java.util.concurrent.CopyOnWriteArrayList<String>()
        server.dispatcher = assetDispatcher(puts, java.util.concurrent.CopyOnWriteArrayList())
        val note = repository.create("缺文件", "f", 10, 10)
        val img = com.adnote.model.ImageItem(path = "images/gone.jpg", x = 0f, y = 0f, width = 1f, height = 1f)
        repository.save(note.copy(pages = listOf(note.pages[0].copy(images = listOf(img)))))

        val result = syncEngine.sync()
        assertEquals(1, result.success)
        assertFalse(puts.any { it.endsWith("gone.jpg") })
        assertTrue(repository.load(note.id)!!.sync.uploadedAssets.isEmpty())
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew testDebugUnitTest --tests "com.adnote.sync.*" -q`
Expected: `NoteAssets` 未定义；`testImagesAndPdfUploadedOnceAndStaleAssetsDeleted` 失败。

- [ ] **Step 3: 实现**

创建 `app/src/main/java/com/adnote/sync/NoteAssets.kt`：

```kotlin
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
```

`SyncEngine.syncSingleNote` 中，从 `// 录音只上传一次（按 id 记录），文件较大` 到该 for 循环结束整段替换为：

```kotlin
        // 附件（录音、图片、背景、PDF）每个只传一次；本地删掉的在远端也删掉；
        // 笔迹目录 MOVE 失败时远端等于空目录，全部重传
        val previouslyUploaded = if (movedInk) NoteAssets.previouslyUploaded(originalNote) else emptySet()
        val referenced = NoteAssets.referenced(noteWithMerged)
        val uploadedAssets = ArrayList<String>()
        for (rel in referenced) {
            if (rel in previouslyUploaded) { uploadedAssets += rel; continue }
            val file = repository.assetFile(originalNote.id, rel)
            if (!file.isFile) continue
            webDavClient.putFile("$targetInkDir/$rel", file, NoteAssets.mimeType(rel))
            uploadedAssets += rel
        }
        for (stale in previouslyUploaded - referenced.toSet()) {
            runCatching { webDavClient.delete("$targetInkDir/$stale") }
        }
```

第 6 步 `SyncState(...)` 里把 `uploadedRecordings = uploaded.filter { ... }` 这一行替换为：

```kotlin
            uploadedAssets = uploadedAssets,
```

删除不再使用的 `val uploaded = originalNote.sync.uploadedRecordings.toMutableSet()` 声明。

- [ ] **Step 4: 运行全部测试确认通过**

Run: `./gradlew testDebugUnitTest -q`
Expected: PASS，含已改写断言的 `testRecordingsUploadOnce`。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/adnote/sync/NoteAssets.kt app/src/main/java/com/adnote/sync/SyncEngine.kt app/src/test/java/com/adnote/sync/NoteAssetsTest.kt app/src/test/java/com/adnote/sync/SyncEngineTest.kt
git commit -m "feat(sync): upload every note asset once, delete stale assets remotely"
```

---

### Task 6: 拉取阶段：把 Obsidian 端修改带回未改动的笔记

**Files:**
- Modify: `app/src/main/java/com/adnote/sync/SyncEngine.kt`（`SyncResult` 加 `pulled`；`sync` 末尾调用 `pullRemoteEdits`）
- Modify: `app/src/main/java/com/adnote/ui/MainActivity.kt:583-587`（toast 文案）
- Test: `app/src/test/java/com/adnote/sync/SyncEngineTest.kt`

**Interfaces:**
- Consumes: `WebDavClient.list`（Task 1）、`RemoteChange`、`TagMerge`、`MarkdownComposer.userContent`
- Produces: `SyncResult.pulled: Int`；`private fun SyncEngine.pullRemoteEdits(notes: List<Note>): Int`

- [ ] **Step 1: 写失败测试**

`SyncEngineTest.kt` 追加：

```kotlin
    /** 内存 WebDAV：按路径存内容与 ETag，支持 PROPFIND Depth 1。 */
    private inner class MemoryDav : Dispatcher() {
        val files = ConcurrentHashMap<String, Pair<String, String>>() // 相对根目录路径 -> (内容, etag)
        private fun rel(request: RecordedRequest) =
            java.net.URLDecoder.decode(request.path.orEmpty(), "UTF-8").removePrefix("/AdNote").trim('/')

        override fun dispatch(request: RecordedRequest): MockResponse {
            val p = rel(request)
            return when (request.method) {
                "MKCOL" -> MockResponse().setResponseCode(201)
                "GET" -> files[p]?.let { MockResponse().setResponseCode(200).setBody(it.first).setHeader("ETag", it.second) }
                    ?: MockResponse().setResponseCode(404)
                "PUT" -> {
                    val etag = "\"v${files.size + 1}-${System.nanoTime()}\""
                    files[p] = request.body.readUtf8() to etag
                    MockResponse().setResponseCode(201).setHeader("ETag", etag)
                }
                "DELETE" -> { files.remove(p); MockResponse().setResponseCode(204) }
                "PROPFIND" -> {
                    val children = files.keys.filter { it.substringBeforeLast('/', "") == p }
                    val xml = buildString {
                        append("""<?xml version="1.0"?><D:multistatus xmlns:D="DAV:">""")
                        append("<D:response><D:href>/AdNote/$p/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>")
                        for (c in children) {
                            append("<D:response><D:href>/AdNote/$c</D:href><D:propstat><D:prop><D:resourcetype/>")
                            append("<D:getetag>${files[c]!!.second}</D:getetag></D:prop></D:propstat></D:response>")
                        }
                        append("</D:multistatus>")
                    }
                    MockResponse().setResponseCode(207).setBody(xml)
                }
                else -> MockResponse().setResponseCode(200)
            }
        }
    }

    /** 模拟在 Obsidian 里改 tags、在用户区加正文，生成区原样保留。 */
    private fun withObsidianEdits(md: String, tags: String, userText: String): String {
        val fm = md.substringBefore("\n---\n", "")
        val body = md.substringAfter("\n---\n")
        val newFm = fm.lines().filterNot { it.startsWith("tags:") || it.startsWith("  - ") }.joinToString("\n") + "\ntags: [$tags]"
        return "$newFm\n---\n$userText\n\n" + body.substring(body.indexOf("<!-- adnote:begin"))
    }

    @Test
    fun testPullBringsObsidianEditsToCleanNote() {
        val dav = MemoryDav()
        server.dispatcher = dav
        val created = repository.create("拉取", "工作", 10, 10)
        repository.save(created.copy(tags = listOf("设备")))
        assertEquals(1, syncEngine.sync().success)
        val mdPath = repository.load(created.id)!!.sync.remoteMdPath!!
        assertFalse(repository.load(created.id)!!.isDirty)

        // 在 Obsidian 里改标签、加正文
        val (md, _) = dav.files[mdPath]!!
        dav.files[mdPath] = withObsidianEdits(md, "设备, 电脑", "电脑上补充的想法") to "\"edited-on-pc\""

        val result = syncEngine.sync()
        assertEquals(1, result.pulled)
        val pulled = repository.load(created.id)!!
        assertEquals(listOf("设备", "电脑"), pulled.tags)
        assertEquals("电脑上补充的想法", pulled.userMarkdown)
        assertEquals("\"edited-on-pc\"", pulled.sync.remoteMdEtag)

        // 再同步一次：ETag 相同，不再拉取
        assertEquals(0, syncEngine.sync().pulled)
    }

    @Test
    fun testPullSkipsLocallyDirtyNote() {
        val dav = MemoryDav()
        server.dispatcher = dav
        val note = repository.create("脏笔记", "工作", 10, 10)
        syncEngine.sync()
        val mdPath = repository.load(note.id)!!.sync.remoteMdPath!!
        val (md, _) = dav.files[mdPath]!!
        dav.files[mdPath] = withObsidianEdits(md, "电脑", "正文") to "\"pc\""

        // 本地又改了：这篇会走推送合并，不走拉取
        val local = repository.load(note.id)!!
        repository.save(local.copy(tags = listOf("本地"), updatedAt = maxOf(local.updatedAt, local.sync.lastSyncedAt) + 1000))
        val result = syncEngine.sync()
        assertEquals(0, result.pulled)
        assertEquals(1, result.success)
        assertEquals(listOf("本地", "电脑"), repository.load(note.id)!!.tags)
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew testDebugUnitTest --tests "com.adnote.sync.SyncEngineTest" -q`
Expected: `pulled` 未定义，编译失败。

- [ ] **Step 3: 实现**

`SyncResult` 改为：

```kotlin
data class SyncResult(
    val total: Int,
    val success: Int,
    val failed: Int,
    val firstError: String? = null,
    /** 拉取阶段从 Obsidian 端带回修改的笔记数。 */
    val pulled: Int = 0,
)
```

`sync()` 中，在末尾那次 `processTombstones()` 之前插入：

```kotlin
        // 3. 拉取：本地没改、远端可能在 Obsidian 里改过的笔记
        val pulled = try {
            pullRemoteEdits(allNotes.filter { it !in dirtyNotes && it.sync.remoteMdPath != null })
        } catch (e: WebDavAuthException) {
            return SyncResult(total = dirtyNotes.size, success = successCount, failed = failedCount, firstError = e.message)
        }
```

并把最终 `return SyncResult(...)` 加上 `pulled = pulled`。

在类中添加：

```kotlin
    /**
     * 按远端文件夹各做一次 PROPFIND 拿 ETag；ETag 不同或拿不到 ETag 的再 GET，
     * 用内容哈希确认变化后合并标签、采用远端正文。合并后标签与远端不同时标记待推送。
     */
    private fun pullRemoteEdits(notes: List<Note>): Int {
        var pulled = 0
        val byFolder = notes.groupBy { it.sync.remoteMdPath!!.substringBeforeLast('/', "") }
        for ((folder, group) in byFolder) {
            val entries = try {
                webDavClient.list(folder).associateBy { it.path }
            } catch (e: WebDavAuthException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            for (note in group) {
                val mdPath = note.sync.remoteMdPath!!
                val entry = entries[mdPath] ?: continue
                val etagSame = entry.etag != null && note.sync.remoteMdEtag != null && entry.etag == note.sync.remoteMdEtag
                if (etagSame) continue
                val resp = runCatching { webDavClient.get(mdPath) }.getOrNull() ?: continue
                val body = resp.body.orEmpty()
                if (!RemoteChange.detect(note.sync.remoteMdEtag, note.sync.remoteMdHash, resp.etag, body)) continue

                val latest = repository.load(note.id) ?: continue
                if (latest.isDirty) continue
                val parsed = MarkdownComposer.parse(body)
                val remoteTags = parsed.tags ?: emptyList()
                val tags = TagMerge.merge(latest.sync.syncedTags, latest.tags, remoteTags)
                val userMd = MarkdownComposer.userContent(parsed) ?: latest.userMarkdown
                val needsPush = tags != remoteTags
                repository.save(
                    latest.copy(
                        tags = tags,
                        userMarkdown = userMd,
                        updatedAt = if (needsPush) System.currentTimeMillis() else latest.updatedAt,
                        sync = latest.sync.copy(
                            remoteMdEtag = resp.etag ?: latest.sync.remoteMdEtag,
                            remoteMdHash = ContentHash.sha256(body),
                            syncedTags = if (needsPush) latest.sync.syncedTags else tags,
                        ),
                    )
                )
                pulled++
            }
        }
        return pulled
    }
```

`MainActivity.triggerSync` 的 `val msg = ...` 改为：

```kotlin
            val pulledText = if (result.pulled > 0) "，拉取 Obsidian 修改 ${result.pulled} 篇" else ""
            val msg = if (result.failed == 0) {
                "同步完成：成功 ${result.success} 篇笔记$pulledText"
            } else {
                "同步完成：成功 ${result.success}，失败 ${result.failed}$pulledText\n错误: ${result.firstError}"
            }
```

- [ ] **Step 4: 运行全部测试确认通过**

Run: `./gradlew testDebugUnitTest -q`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/adnote/sync/SyncEngine.kt app/src/main/java/com/adnote/ui/MainActivity.kt app/src/test/java/com/adnote/sync/SyncEngineTest.kt
git commit -m "feat(sync): pull Obsidian edits for notes not modified locally"
```

---

### Task 7: RestoreEngine：扫描远端并恢复笔记与附件

**Files:**
- Create: `app/src/main/java/com/adnote/sync/RestoreEngine.kt`
- Modify: `app/src/main/java/com/adnote/storage/NoteRepository.kt`（新增 `importNote`）
- Test: `app/src/test/java/com/adnote/sync/RestoreEngineTest.kt`
- Test: `app/src/test/java/com/adnote/storage/NoteRepositoryTest.kt`

**Interfaces:**
- Consumes: `WebDavClient.list/get/download`（Task 1）、`NoteAssets.referenced`（Task 5）
- Produces:
  - `enum class LocalState(val displayName: String) { MISSING, OLDER, SAME_OR_NEWER }`
  - `data class RemoteNoteInfo(val note: Note, val folder: String, val inkDir: String, val localState: LocalState)`
  - `data class RestoreResult(val restored: Int, val failed: Int, val firstError: String?)`
  - `class RestoreEngine(repository, webDavClient) { fun scan(maxDepth: Int = 6): List<RemoteNoteInfo>; fun restore(infos: List<RemoteNoteInfo>): RestoreResult }`
  - `fun NoteRepository.importNote(note: Note)`：清掉待写队列与同步状态缓存、回收站副本、墓碑，然后落盘。

- [ ] **Step 1: 写失败测试**

`NoteRepositoryTest.kt` 追加：

```kotlin
    @Test
    fun testImportNoteReplacesTrashAndTombstone() {
        val repo = NoteRepository(tempFolder.root)
        val note = repo.create("导入", "f", 10, 10)
        val synced = note.copy(sync = SyncState(lastSyncedAt = 100L, remoteMdPath = "f/导入.md"))
        repo.save(synced)
        repo.moveToTrash(synced, "f/_ink/${note.id}")
        assertEquals(1, repo.tombstones().size)
        assertEquals(1, repo.trashCount())

        val fromCloud = synced.copy(title = "云端版本", updatedAt = 999L, sync = SyncState(lastSyncedAt = 999L, remoteMdPath = "f/云端版本.md"))
        repo.importNote(fromCloud)

        assertEquals(0, repo.trashCount())
        assertTrue(repo.tombstones().isEmpty())
        val loaded = repo.load(note.id)!!
        assertEquals("云端版本", loaded.title)
        assertEquals("f/云端版本.md", loaded.sync.remoteMdPath)
    }
```

创建 `app/src/test/java/com/adnote/sync/RestoreEngineTest.kt`：

```kotlin
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
import org.junit.Assert.assertNotNull
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
                    "GET" -> files[p]?.let { MockResponse().setResponseCode(200).setBody(okio.Buffer().write(it)) }
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

    private fun putRemoteNote(folder: String, note: Note, extra: Map<String, ByteArray> = emptyMap()) {
        val inkDir = "$folder/_ink/${note.id}"
        dirs[""] = ((dirs[""] ?: emptyList()) + (folder to true)).distinct()
        dirs[folder] = listOf("_ink" to true, "${note.title}.md" to false)
        dirs["$folder/_ink"] = (dirs["$folder/_ink"] ?: emptyList()) + (note.id to true)
        dirs[inkDir] = listOf("ink.json" to false) + extra.keys.map { it to false }
        files["$inkDir/ink.json"] = NoteJson.encodeToString(Note.serializer(), note).toByteArray()
        extra.forEach { (rel, bytes) -> files["$inkDir/$rel"] = bytes }
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
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew testDebugUnitTest --tests "com.adnote.sync.RestoreEngineTest" --tests "com.adnote.storage.NoteRepositoryTest" -q`
Expected: 编译失败，`RestoreEngine`、`importNote` 未定义。

- [ ] **Step 3: 实现**

`NoteRepository.kt` 在 `restore` 函数之后添加：

```kotlin
    /**
     * 从云端导入（覆盖本地同 id 的笔记）：丢掉排队中的保存与缓存的同步状态，
     * 清掉回收站副本和墓碑，然后落盘。附件文件由调用方事先写到笔记目录。
     */
    fun importNote(note: Note) {
        flush()
        pending.remove(note.id)
        syncStates.remove(note.id)
        File(trashDir, note.id).deleteRecursively()
        clearTombstone(note.id)
        save(note)
    }
```

创建 `app/src/main/java/com/adnote/sync/RestoreEngine.kt`：

```kotlin
package com.adnote.sync

import com.adnote.export.RemotePaths
import com.adnote.model.Note
import com.adnote.model.NoteJson
import com.adnote.model.SyncState
import com.adnote.storage.NoteRepository
import java.io.File

enum class LocalState(val displayName: String) {
    MISSING("本地不存在"),
    OLDER("本地较旧"),
    SAME_OR_NEWER("已是最新"),
}

/** 远端找到的一篇笔记。[folder] 是 md 所在的远端目录（相对根目录，根目录为空串），[inkDir] 是 _ink/<id> 的相对路径。 */
data class RemoteNoteInfo(
    val note: Note,
    val folder: String,
    val inkDir: String,
    val localState: LocalState,
)

data class RestoreResult(val restored: Int, val failed: Int, val firstError: String?)

/**
 * 从 WebDAV 恢复笔记：遍历远端目录树找 `_ink/<id>/ink.json`，下载笔记与附件写回本地。
 * 恢复后的笔记视为「已同步到 updatedAt 那一刻」，不会立即重新上传；ETag 未知，下次同步会重新拉取一次远端 md 合并。
 */
class RestoreEngine(
    private val repository: NoteRepository,
    private val webDavClient: WebDavClient,
) {

    fun scan(maxDepth: Int = 6): List<RemoteNoteInfo> {
        val found = ArrayList<RemoteNoteInfo>()
        walk("", 0, maxDepth, found)
        return found.sortedByDescending { it.note.updatedAt }
    }

    private fun walk(dir: String, depth: Int, maxDepth: Int, out: MutableList<RemoteNoteInfo>) {
        if (depth > maxDepth) return
        val entries = webDavClient.list(dir)
        entries.firstOrNull { it.isDir && it.name == "_ink" }?.let { inkRoot ->
            for (noteDir in webDavClient.list(inkRoot.path).filter { it.isDir }) {
                val json = runCatching { webDavClient.get("${noteDir.path}/ink.json") }.getOrNull() ?: continue
                val note = runCatching { NoteJson.decodeFromString(Note.serializer(), json.body.orEmpty()) }.getOrNull() ?: continue
                out += RemoteNoteInfo(note, dir, noteDir.path, localState(note))
            }
        }
        for (e in entries) {
            if (e.isDir && e.name != "_ink") walk(e.path, depth + 1, maxDepth, out)
        }
    }

    private fun localState(remote: Note): LocalState {
        val local = repository.load(remote.id) ?: return LocalState.MISSING
        return if (local.updatedAt < remote.updatedAt) LocalState.OLDER else LocalState.SAME_OR_NEWER
    }

    fun restore(infos: List<RemoteNoteInfo>): RestoreResult {
        var restored = 0
        var failed = 0
        var firstError: String? = null
        for (info in infos) {
            try {
                restoreOne(info)
                restored++
            } catch (e: WebDavAuthException) {
                return RestoreResult(restored, infos.size - restored, e.message)
            } catch (e: Exception) {
                failed++
                if (firstError == null) firstError = "${info.note.title}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        return RestoreResult(restored, failed, firstError)
    }

    private fun restoreOne(info: RemoteNoteInfo) {
        val note = info.note
        val dir = repository.getNoteDir(note.id).apply { mkdirs() }
        val downloaded = ArrayList<String>()
        for (rel in NoteAssets.referenced(note)) {
            if (webDavClient.download("${info.inkDir}/$rel", File(dir, rel))) downloaded += rel
        }
        val mdName = "${RemotePaths.sanitize(note.title)}.md"
        val mdPath = if (info.folder.isEmpty()) mdName else "${info.folder}/$mdName"
        val sync = SyncState(
            lastSyncedAt = note.updatedAt,
            remoteMdPath = mdPath,
            remoteMdEtag = null,
            remoteMdHash = null,
            syncedTags = note.tags,
            remotePageCount = note.pages.size,
            uploadedAssets = downloaded,
        )
        repository.importNote(note.copy(sync = sync))
    }
}
```

- [ ] **Step 4: 运行全部测试确认通过**

Run: `./gradlew testDebugUnitTest -q`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/adnote/sync/RestoreEngine.kt app/src/main/java/com/adnote/storage/NoteRepository.kt app/src/test/java/com/adnote/sync/RestoreEngineTest.kt app/src/test/java/com/adnote/storage/NoteRepositoryTest.kt
git commit -m "feat(sync): RestoreEngine scans WebDAV and restores notes with assets"
```

---

### Task 8: 设置页「从云端恢复笔记」入口

**Files:**
- Modify: `app/src/main/res/layout/activity_settings.xml:170-182`（保存按钮所在 LinearLayout 之后）
- Modify: `app/src/main/java/com/adnote/ui/SettingsActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `RestoreEngine.scan/restore`、`RemoteNoteInfo`、`LocalState`（Task 7）

无法单元测试（Android UI），用模拟器手工验证。

- [ ] **Step 1: 布局与文案**

`strings.xml` 在 `settings_save` 之后加：

```xml
    <string name="settings_restore_from_cloud">从云端恢复笔记</string>
```

`activity_settings.xml` 中，找到 id 为 `btnSaveSettings` 的 `<Button>` 所在的水平 `LinearLayout` 的闭合标签 `</LinearLayout>`（第 181 行附近），在它后面、外层卡片 `</LinearLayout>` 之前插入：

```xml
            <Button
                android:id="@+id/btnRestoreFromCloud"
                android:layout_width="match_parent"
                android:layout_height="36dp"
                android:layout_marginTop="8dp"
                android:text="@string/settings_restore_from_cloud"
                android:textSize="12sp"
                android:textColor="@color/text_primary"
                android:background="@drawable/bg_button_secondary"
                android:stateListAnimator="@null" />
```

- [ ] **Step 2: Activity 逻辑**

`SettingsActivity.kt`：字段区加 `private lateinit var btnRestoreFromCloud: Button`；`initViews()` 末尾加 `btnRestoreFromCloud = findViewById(R.id.btnRestoreFromCloud)`；`setupListeners()` 末尾加：

```kotlin
        btnRestoreFromCloud.setOnClickListener { startRestoreFromCloud() }
```

在文件顶部 import 区加入：

```kotlin
import com.adnote.sync.LocalState
import com.adnote.sync.RemoteNoteInfo
import com.adnote.sync.RestoreEngine
```

类中添加：

```kotlin
    private fun startRestoreFromCloud() {
        val settings = getSettingsFromInput()
        if (!settings.isConfigured) {
            Toast.makeText(this, "请先填写完整的服务器地址、用户名及密码", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "正在扫描远端笔记...", Toast.LENGTH_SHORT).show()
        val engine = RestoreEngine(
            AdNoteApp.instance.repository,
            WebDavClient(settings.serverUrl, settings.username, settings.password, settings.remoteRootDir),
        )
        lifecycleScope.launch {
            val scanned = withContext(Dispatchers.IO) { runCatching { engine.scan() } }
            val infos = scanned.getOrElse {
                Toast.makeText(this@SettingsActivity, "扫描失败: ${it.message}", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (infos.isEmpty()) {
                Toast.makeText(this@SettingsActivity, "远端没有找到 AdNote 笔记", Toast.LENGTH_LONG).show()
                return@launch
            }
            showRestoreDialog(engine, infos)
        }
    }

    private fun showRestoreDialog(engine: RestoreEngine, infos: List<RemoteNoteInfo>) {
        val labels = infos.map { "${it.note.title}  ·  ${it.folder.ifEmpty { "根目录" }}  ·  ${it.localState.displayName}" }.toTypedArray()
        val checked = BooleanArray(infos.size) { infos[it].localState != LocalState.SAME_OR_NEWER }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择要恢复的笔记（${infos.size} 篇）")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("恢复") { _, _ ->
                val chosen = infos.filterIndexed { i, _ -> checked[i] }
                if (chosen.isEmpty()) return@setPositiveButton
                Toast.makeText(this, "正在恢复 ${chosen.size} 篇...", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { engine.restore(chosen) }
                    val msg = if (result.failed == 0) "已恢复 ${result.restored} 篇笔记"
                    else "恢复 ${result.restored} 篇，失败 ${result.failed}\n${result.firstError}"
                    Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
```

- [ ] **Step 3: 编译并在模拟器验证**

Run: `./gradlew assembleDebug -q && ./gradlew testDebugUnitTest -q`
Expected: 构建成功、测试通过。

手工验证（模拟器 + 本机 WebDAV，例如 `rclone serve webdav ./dav --addr :8080`，模拟器内地址 `http://10.0.2.2:8080/`）：
1. 新建笔记、写几笔、插一张图、录 5 秒音，点同步。
2. 系统设置 → 应用 → AdNote → 清除数据。
3. 重新打开，填 WebDAV 配置，点「从云端恢复笔记」，勾选，恢复。
4. 打开笔记：笔迹、图片、录音都在；主界面不显示「待同步」。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/res/layout/activity_settings.xml app/src/main/java/com/adnote/ui/SettingsActivity.kt app/src/main/res/values/strings.xml
git commit -m "feat(settings): restore notes from WebDAV"
```

---

### Task 9: 锁定笔记只按标题搜索

**Files:**
- Modify: `app/src/main/java/com/adnote/storage/NoteSummary.kt:37-45`（`filter`）
- Modify: `app/src/main/java/com/adnote/storage/NoteRepository.kt`（companion `filter`）
- Test: `app/src/test/java/com/adnote/storage/NoteSummaryTest.kt`（新建）

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/adnote/storage/NoteSummaryTest.kt`：

```kotlin
package com.adnote.storage

import com.adnote.model.Note
import com.adnote.model.Page
import com.adnote.model.PinLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSummaryTest {

    private val locked = Note(
        title = "机密会议", folder = "工作",
        pages = listOf(Page(width = 10, height = 10, recognizedText = "并购金额一千万")),
        createdAt = 0L, updatedAt = 0L, lockHash = PinLock.hash("1234"),
    )
    private val open = Note(
        title = "普通", folder = "工作",
        pages = listOf(Page(width = 10, height = 10, recognizedText = "并购流程")),
        createdAt = 0L, updatedAt = 0L,
    )

    @Test
    fun lockedNoteMatchesTitleOnly() {
        val items = listOf(NoteSummary.of(locked), NoteSummary.of(open))
        assertEquals(listOf("普通"), NoteSummary.filter(items, "并购").map { it.title })
        assertEquals(listOf("机密会议"), NoteSummary.filter(items, "机密").map { it.title })
        assertEquals(2, NoteSummary.filter(items, "").size)
    }

    @Test
    fun repositoryFilterBehavesTheSame() {
        assertEquals(listOf("普通"), NoteRepository.filter(listOf(locked, open), "并购").map { it.title })
        assertTrue(NoteRepository.filter(listOf(locked, open), "机密").any { it.title == "机密会议" })
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew testDebugUnitTest --tests "com.adnote.storage.NoteSummaryTest" -q`
Expected: `lockedNoteMatchesTitleOnly` 失败（锁定笔记被「并购」命中）。

- [ ] **Step 3: 实现**

`NoteSummary.filter` 中 `(q.isEmpty() || n.searchText.lowercase().contains(q))` 改为：

```kotlin
                    (q.isEmpty() || (if (n.isLocked) n.title else n.searchText).lowercase().contains(q))
```

`NoteRepository.Companion.filter` 中 `(q.isEmpty() || n.searchableText().lowercase().contains(q))` 改为：

```kotlin
                    (q.isEmpty() || (if (n.isLocked) n.title else n.searchableText()).lowercase().contains(q))
```

- [ ] **Step 4: 运行全部测试确认通过**

Run: `./gradlew testDebugUnitTest -q`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/adnote/storage/NoteSummary.kt app/src/main/java/com/adnote/storage/NoteRepository.kt app/src/test/java/com/adnote/storage/NoteSummaryTest.kt
git commit -m "fix(search): locked notes match by title only"
```
