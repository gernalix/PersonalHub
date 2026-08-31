package com.example.multitimetracker.capsules.remotesync

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import javax.net.ssl.HttpsURLConnection

internal class DatasetteWriteClient(private val config: RemoteSyncConfig) {
    sealed interface Result {
        data object Success : Result
        data class Failure(val disposition: HttpDisposition, val status: Int?) : Result
    }

    fun upsert(items: List<RemoteSyncQueueSqlite.QueueItem>): Result {
        require(items.isNotEmpty())
        val baseUrl = RemoteSyncContract.validateBaseUrl(config.baseUrl)
            ?: return Result.Failure(HttpDisposition.PERMANENT_FAILURE, null)
        if (!config.isEnabled) return Result.Failure(HttpDisposition.AUTH_FAILURE, null)
        val connection = URI(RemoteSyncContract.upsertUrl(baseUrl)).toURL().openConnection() as HttpsURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${config.token}")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            val request = JSONObject()
                .put("rows", JSONArray().also { rows -> items.forEach { rows.put(JSONObject(it.rowJson)) } })
                .put("return", false)
                .toString()
            connection.outputStream.use { output -> output.write(request.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val disposition = RemoteSyncContract.classifyHttpStatus(status)
            if (disposition != HttpDisposition.SUCCESS) return Result.Failure(disposition, status)
            val response = connection.inputStream.bufferedReader().use { it.readText().take(MAX_RESPONSE_CHARS) }
            if (JSONObject(response).optBoolean("ok", false)) Result.Success
            else Result.Failure(HttpDisposition.PERMANENT_FAILURE, status)
        } catch (_: IOException) {
            Result.Failure(HttpDisposition.RETRY, null)
        } catch (_: RuntimeException) {
            Result.Failure(HttpDisposition.PERMANENT_FAILURE, null)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        private const val MAX_RESPONSE_CHARS = 64 * 1024
    }
}
