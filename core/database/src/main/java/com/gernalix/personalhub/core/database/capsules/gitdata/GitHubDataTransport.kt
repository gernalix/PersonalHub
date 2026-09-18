package com.gernalix.personalhub.core.database.capsules.gitdata

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

internal class GitHubDataTransport(
    private val repository: GitRepository,
    private val token: String,
) {
    private val api = "https://api.github.com/repos/${repository.owner}/${repository.name}"

    data class RemoteHead(
        val branch: String,
        val commitSha: String,
        val treeSha: String,
    )

    fun remoteHead(): RemoteHead {
        val repo = json("GET", api)
        val branch = repo.getString("default_branch")
        val ref = json("GET", "$api/git/ref/heads/${encodeSegment(branch)}")
        val commitSha = ref.getJSONObject("object").getString("sha")
        val commit = json("GET", "$api/git/commits/$commitSha")
        return RemoteHead(
            branch = branch,
            commitSha = commitSha,
            treeSha = commit.getJSONObject("tree").getString("sha"),
        )
    }

    fun readFile(path: String, ref: String): ByteArray {
        val url = "$api/contents/${encodePath(path)}?ref=${encodeQuery(ref)}"
        return request(
            method = "GET",
            url = url,
            accept = "application/vnd.github.raw+json",
        )
    }

    fun readFileOrNull(path: String, ref: String): ByteArray? {
        val url = "$api/contents/${encodePath(path)}?ref=${encodeQuery(ref)}"
        return requestOrNull(
            method = "GET",
            url = url,
            accept = "application/vnd.github.raw+json",
        )
    }

    fun pushFiles(
        files: Map<String, ByteArray>,
        message: String,
        expectedHead: RemoteHead? = null,
    ): String {
        require(files.isNotEmpty())
        val head = expectedHead ?: remoteHead()
        val treeEntries = JSONArray()
        files.toSortedMap().forEach { (path, bytes) ->
            require(bytes.size <= 100 * 1024 * 1024) { "Git file exceeds GitHub blob limit: $path" }
            val blob = json(
                "POST",
                "$api/git/blobs",
                JSONObject()
                    .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    .put("encoding", "base64"),
            )
            treeEntries.put(
                JSONObject()
                    .put("path", path)
                    .put("mode", "100644")
                    .put("type", "blob")
                    .put("sha", blob.getString("sha")),
            )
        }
        val tree = json(
            "POST",
            "$api/git/trees",
            JSONObject()
                .put("base_tree", head.treeSha)
                .put("tree", treeEntries),
        )
        val commit = json(
            "POST",
            "$api/git/commits",
            JSONObject()
                .put("message", message)
                .put("tree", tree.getString("sha"))
                .put("parents", JSONArray().put(head.commitSha)),
        )
        val commitSha = commit.getString("sha")
        json(
            "PATCH",
            "$api/git/refs/heads/${encodeSegment(head.branch)}",
            JSONObject().put("sha", commitSha).put("force", false),
        )
        return commitSha
    }

    private fun json(method: String, url: String, body: JSONObject? = null): JSONObject =
        JSONObject(
            String(
                request(
                    method = method,
                    url = url,
                    body = body?.toString()?.toByteArray(Charsets.UTF_8),
                    accept = "application/vnd.github+json",
                    contentType = if (body == null) null else "application/json; charset=utf-8",
                ),
                Charsets.UTF_8,
            ),
        )

    private fun requestOrNull(
        method: String,
        url: String,
        accept: String,
    ): ByteArray? {
        val connection = connection(method, url, accept, null)
        return try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
            require(code in 200..299) { errorMessage(connection, code) }
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun request(
        method: String,
        url: String,
        body: ByteArray? = null,
        accept: String,
        contentType: String? = null,
    ): ByteArray {
        val connection = connection(method, url, accept, contentType)
        return try {
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            require(code in 200..299) { errorMessage(connection, code) }
            if (code == HttpURLConnection.HTTP_NO_CONTENT) ByteArray(0)
            else connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun connection(
        method: String,
        url: String,
        accept: String,
        contentType: String?,
    ): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 45_000
            useCaches = false
            setRequestProperty("Accept", accept)
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "PersonalHub-GitDataSync")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            if (contentType != null) setRequestProperty("Content-Type", contentType)
        }

    private fun errorMessage(connection: HttpURLConnection, code: Int): String {
        val body = runCatching {
            connection.errorStream?.use { input ->
                val output = ByteArrayOutputStream()
                input.copyTo(output)
                output.toString(Charsets.UTF_8.name()).take(1000)
            }
        }.getOrNull()
        return "GitHub request failed ($code)${body?.let { ": $it" } ?: ""}"
    }

    private fun encodeSegment(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun encodeQuery(value: String): String = encodeSegment(value)

    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { encodeSegment(it) }
}
