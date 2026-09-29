package com.adnote.sync

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

open class WebDavException(message: String, val statusCode: Int = 0) : IOException(message)
class WebDavAuthException(message: String = "WebDAV 认证失败，请检查账号密码或应用授权码") : WebDavException(message, 401)

data class WebDavResponse(
    val statusCode: Int,
    val etag: String?,
    val body: String?,
)

/** PROPFIND 列出的一个条目。[path] 相对远端根目录，不带首尾斜杠。 */
data class DavEntry(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val etag: String?,
)

class WebDavClient(
    val baseUrl: String,
    username: String = "",
    password: String = "",
    val remoteRootDir: String = "",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) {

    private val authHeader: String? = if (username.isNotBlank()) {
        Credentials.basic(username, password)
    } else null

    fun resolveUrl(relativePath: String): HttpUrl {
        val root = baseUrl.toHttpUrl().newBuilder()
        val allSegments = listOf(remoteRootDir, relativePath)
            .flatMap { it.split('/') }
            .filter { it.isNotBlank() }

        for (segment in allSegments) {
            root.addPathSegment(segment)
        }
        return root.build()
    }

    private fun newRequestBuilder(url: HttpUrl): Request.Builder {
        val builder = Request.Builder().url(url)
        authHeader?.let { builder.header("Authorization", it) }
        return builder
    }

    private val verifiedDirs = HashSet<String>()

    fun clearDirCache() {
        verifiedDirs.clear()
    }

    /** 递归确保目标路径所在的所有父目录均已创建。 */
    fun ensureParentDirs(filePath: String) {
        val segments = filePath.split('/').filter { it.isNotBlank() }
        if (segments.size <= 1) return // 只有文件名，不需要建父目录

        val dirSegments = segments.dropLast(1)
        var currentPath = ""
        for (seg in dirSegments) {
            currentPath = if (currentPath.isEmpty()) seg else "$currentPath/$seg"
            if (verifiedDirs.add(currentPath)) {
                mkcol(currentPath)
            }
        }
    }

    /** 创建单个目录。如果已存在（405）或成功（201/200）则返回成功。 */
    fun mkcol(dirPath: String) {
        val url = resolveUrl(dirPath)
        val request = newRequestBuilder(url)
            .method("MKCOL", null)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 401) throw WebDavAuthException()
            // 201 Created, 405 Method Not Allowed (已经存在), 200 OK
            if (response.isSuccessful || response.code == 405) {
                return
            }
            // 部分 WebDAV 服务器在已存在时返回 409 或 301
            if (response.code == 409 || response.code == 301) {
                return
            }
            throw WebDavException("MKCOL 失败 (${response.code}) 目录: $dirPath", response.code)
        }
    }

    fun put(
        relativePath: String,
        content: String,
        contentType: String = "text/markdown; charset=utf-8",
    ): WebDavResponse = executePut(relativePath, content.toRequestBody(contentType.toMediaType()))

    /** 上传二进制文件（录音等）。 */
    fun putBytes(relativePath: String, bytes: ByteArray, contentType: String): WebDavResponse =
        executePut(relativePath, bytes.toRequestBody(contentType.toMediaType()))

    /** PUT 的公共部分：先确保父目录存在，再发请求，统一处理 401 与成功状态码并取回 ETag。 */
    private fun executePut(relativePath: String, body: RequestBody): WebDavResponse {
        ensureParentDirs(relativePath)
        val request = newRequestBuilder(resolveUrl(relativePath)).put(body).build()
        return client.newCall(request).execute().use { response ->
            if (response.code == 401) throw WebDavAuthException()
            if (!response.isSuccessful && response.code != 201 && response.code != 204) {
                throw WebDavException("PUT 失败 (${response.code}): $relativePath", response.code)
            }
            WebDavResponse(statusCode = response.code, etag = response.header("ETag"), body = null)
        }
    }

    fun get(relativePath: String): WebDavResponse? {
        val url = resolveUrl(relativePath)
        val request = newRequestBuilder(url).get().build()

        return client.newCall(request).execute().use { response ->
            if (response.code == 401) throw WebDavAuthException()
            if (response.code == 404) return null
            if (!response.isSuccessful) {
                throw WebDavException("GET 失败 (${response.code}): $relativePath", response.code)
            }
            WebDavResponse(
                statusCode = response.code,
                etag = response.header("ETag"),
                body = response.body?.string().orEmpty(),
            )
        }
    }

    fun delete(relativePath: String): Boolean {
        val url = resolveUrl(relativePath)
        val request = newRequestBuilder(url).delete().build()

        return client.newCall(request).execute().use { response ->
            if (response.code == 401) throw WebDavAuthException()
            if (response.code == 404) return true // 远端已经不存在，视为删除成功
            if (!response.isSuccessful && response.code != 204) {
                throw WebDavException("DELETE 失败 (${response.code}): $relativePath", response.code)
            }
            true
        }
    }

    fun move(fromRelativePath: String, toRelativePath: String): Boolean {
        ensureParentDirs(toRelativePath)
        val fromUrl = resolveUrl(fromRelativePath)
        val toUrl = resolveUrl(toRelativePath).toString()

        val request = newRequestBuilder(fromUrl)
            .method("MOVE", null)
            .header("Destination", toUrl)
            .header("Overwrite", "T")
            .build()

        return client.newCall(request).execute().use { response ->
            if (response.code == 401) throw WebDavAuthException()
            if (response.code == 404) return false
            if (!response.isSuccessful && response.code != 201 && response.code != 204) {
                throw WebDavException("MOVE 失败 (${response.code}) 从 $fromRelativePath 到 $toRelativePath", response.code)
            }
            true
        }
    }

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
    fun putFile(relativePath: String, file: java.io.File, contentType: String): WebDavResponse =
        executePut(relativePath, file.asRequestBody(contentType.toMediaType()))

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
                // 目标已存在时直接覆盖；File.renameTo 在 Windows 上不会覆盖已有文件，所以用 Files.move
                java.nio.file.Files.move(
                    part.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } finally {
                part.delete()
            }
            true
        }
    }
}
