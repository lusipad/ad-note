package com.adnote.sync

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
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
    ): WebDavResponse {
        ensureParentDirs(relativePath)
        val url = resolveUrl(relativePath)
        val request = newRequestBuilder(url)
            .put(content.toRequestBody(contentType.toMediaType()))
            .build()

        return client.newCall(request).execute().use { response ->
            if (response.code == 401) throw WebDavAuthException()
            if (!response.isSuccessful && response.code != 201 && response.code != 204) {
                throw WebDavException("PUT 失败 (${response.code}): $relativePath", response.code)
            }
            WebDavResponse(
                statusCode = response.code,
                etag = response.header("ETag"),
                body = null,
            )
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
}
