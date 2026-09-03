package com.gernalix.personalhub.core.database.capsules.sync

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import javax.net.ssl.HttpsURLConnection

/** Existing Datasette JSON Write API: bearer auth, rows, return=false, explicit ok acknowledgement. */
internal object DatasetteClient {
    fun upsert(config: DatasetteConfiguration, token: String, rows: List<JSONObject>): Boolean {
        val connection = URI("${config.baseUrl}/${config.database}/${config.table}/-/upsert").toURL().openConnection() as HttpsURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            val body = JSONObject().put("rows", JSONArray(rows)).put("return", false).toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            if (connection.responseCode !in 200..299) false
            else connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(65536)
                var count = 0
                while (count < buffer.size) { val n = reader.read(buffer, count, buffer.size - count); if (n < 0) break; count += n }
                count < buffer.size && JSONObject(String(buffer, 0, count)).optBoolean("ok", false)
            }
        } catch (_: Exception) { false } finally { connection.disconnect() }
    }
}
