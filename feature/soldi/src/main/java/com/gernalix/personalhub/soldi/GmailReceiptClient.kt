package com.gernalix.personalhub.soldi

import android.content.Context
import androidx.work.*
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class GmailApiFailure(val status: Int) : Exception()
internal object GmailAuthorization {
    suspend fun request(context: Context): AuthorizationResult = suspendCancellableCoroutine { continuation ->
        Identity.getAuthorizationClient(context).authorize(AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/gmail.readonly")))
            .setOptOutIncludingGrantedScopes(true).build())
            .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
            .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(GmailApiFailure(401)) }
    }
}
internal interface GmailTransport { fun get(path: String, query: Map<String, String> = emptyMap()): JSONObject }
internal class GoogleGmailTransport(private val token: String) : GmailTransport {
    override fun get(path: String, query: Map<String, String>): JSONObject {
        require(path == "profile" || path == "messages" || path == "history" || path.matches(Regex("messages/[A-Za-z0-9_-]{1,200}(?:/attachments/[A-Za-z0-9_-]{1,500})?")))
        val params = query.entries.joinToString("&") { (k,v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }
        val connection = URI("https://gmail.googleapis.com/gmail/v1/users/me/$path${if (params.isBlank()) "" else "?$params"}").toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false; connection.connectTimeout = 20000; connection.readTimeout = 30000
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (connection.responseCode != 200) throw GmailApiFailure(connection.responseCode)
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= 4 * 1024 * 1024); output.write(buffer, 0, n) }
                output.toByteArray()
            }
            return JSONObject(bytes.toString(Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
}
internal class GmailReceiptClient(private val store: GmailReceiptStore, private val transport: GmailTransport) {
    suspend fun sync(): GmailCatalog = LOCK.withLock { withContext(Dispatchers.IO) {
        val before = store.read()
        val profile = transport.get("profile")
        val account = profile.getString("emailAddress")
        require(before.account == null || before.account == account) { "Usa lo stesso account Gmail dell’archivio o rimuovi prima la fonte" }
        val baseline = profile.getString("historyId")
        val changed = linkedSetOf<String>()
        var cursor = before.history
        var full = cursor == null
        fun collect(path: String, params: Map<String, String>, history: Boolean) {
            var page: String? = null
            val seen = mutableSetOf<String>()
            var count = 0
            do {
                require(++count <= 100) { "Sincronizzazione troppo ampia" }
                val response = transport.get(path, params + (page?.let { mapOf("pageToken" to it) } ?: emptyMap()))
                val rows = response.optJSONArray(if (history) "history" else "messages")
                if (rows != null) for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    if (history) {
                        val messages = row.optJSONArray("messages") ?: continue
                        for (j in 0 until messages.length()) changed += messages.getJSONObject(j).getString("id")
                    } else changed += row.getString("id")
                }
                require(changed.size <= 5000) { "Troppi messaggi: riduci lo storico" }
                page = response.text("nextPageToken")
                if (page != null) require(seen.add(page))
                if (history && page == null) cursor = response.getString("historyId")
            } while (page != null)
        }
        if (!full) try { collect("history", mapOf("startHistoryId" to requireNotNull(cursor), "maxResults" to "500"), true) }
        catch (error: GmailApiFailure) { if (error.status != 404) throw error; full = true; changed.clear() }
        if (full) {
            collect("messages", mapOf("q" to "newer_than:${before.days}d {subject:receipt subject:invoice subject:order subject:ricevuta subject:fattura subject:ordine subject:kvittering subject:purchase subject:pagamento subject:appointment subject:appuntamento subject:invitation subject:invito subject:booking subject:prenotazione subject:shipment subject:shipped subject:spedizione subject:tracking subject:consegna filename:ics}", "maxResults" to "100"), false)
            cursor = baseline // captured before listing: later arrivals are picked up by the next history pass
        }
        val updates = mutableMapOf<String, GmailReceipt?>()
        val appointments = mutableListOf<GmailAppointment>()
        val unavailableIds = mutableSetOf<String>()
        changed.forEach { id ->
            require(id.matches(Regex("[A-Za-z0-9_-]{1,200}")))
            val message = try { transport.get("messages/$id", mapOf("format" to "full")) }
            catch (error: GmailApiFailure) { if (error.status != 404) throw error; updates[id] = null; unavailableIds += id; return@forEach }
            val labels = message.optJSONArray("labelIds")
            val unavailable = labels != null && (0 until labels.length()).any { labels.getString(it) in setOf("TRASH", "SPAM") }
            if (unavailable) unavailableIds += id
            if (!unavailable) {
                fun hydrateCalendar(part: JSONObject, depth: Int) {
                    require(depth <= 12)
                    val body = part.optJSONObject("body")
                    if (part.optString("mimeType") == "text/calendar" && body?.text("data") == null && body?.text("attachmentId") != null) {
                        val attachmentId = body.getString("attachmentId")
                        require(attachmentId.matches(Regex("[A-Za-z0-9_-]{1,500}")))
                        val attachment = transport.get("messages/$id/attachments/$attachmentId")
                        body.put("data", attachment.getString("data"))
                    }
                    val parts = part.optJSONArray("parts") ?: return
                    require(parts.length() <= 100)
                    for (i in 0 until parts.length()) hydrateCalendar(parts.getJSONObject(i), depth + 1)
                }
                hydrateCalendar(message.getJSONObject("payload"), 0)
                appointments += GmailCalendarParser.parse(message, before.appointments + appointments)
            }
            val receipt = if (unavailable) null else GmailReceiptParser.parse(message)
            updates[id] = receipt?.takeIf { it.receivedAt >= Instant.now().minusSeconds(before.days * 86400L) }
        }
        store.update { current ->
            require(current.history == before.history && current.account == before.account && current.days == before.days)
            val rows = current.receipts.associateBy { it.messageId }.toMutableMap()
            updates.forEach { (id, row) ->
                if (row != null) {
                    val reviewed = rows[id]?.takeIf { it.parser == "revisione confermata" }
                    rows[id] = reviewed?.copy(available = true) ?: row
                } else rows[id]?.let { rows[id] = it.copy(available = false) }
            }
            val cutoff = Instant.now().minusSeconds(current.days * 86400L)
            val events = current.appointments.map { if (it.messageId in unavailableIds) it.copy(cancelled = true) else it }.associateBy { it.uid }.toMutableMap()
            appointments.forEach { event -> if (event.sequence >= (events[event.uid]?.sequence ?: -1)) events[event.uid] = event }
            current.copy(account = account, history = cursor, updatedAt = Instant.now(), status = "complete", appointments = events.values.toList(),
                receipts = rows.values.filter { it.receivedAt >= cutoff || it.messageId in current.links }.sortedByDescending { it.receivedAt })
        }
    } }
    companion object { private val LOCK = Mutex() }
}

internal object GmailReceiptWork {
    fun schedule(context: Context, enabled: Boolean) {
        val work = WorkManager.getInstance(context)
        if (!enabled) { work.cancelUniqueWork("soldi-gmail"); return }
        work.enqueueUniquePeriodicWork("soldi-gmail", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<GmailReceiptWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
}
class GmailReceiptWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = GmailReceiptStore(applicationContext)
        if (!store.read().automatic) return Result.success()
        return try {
            val auth = GmailAuthorization.request(applicationContext)
            if (auth.hasResolution() || auth.accessToken.isNullOrBlank()) {
                store.update { it.copy(status = "authorization") }; return Result.success()
            }
            GmailReceiptClient(store, GoogleGmailTransport(requireNotNull(auth.accessToken))).sync()
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (error: GmailApiFailure) {
            store.update { it.copy(status = if (error.status in setOf(401,403)) "authorization" else "failed") }
            if (error.status in setOf(429,500,502,503,504) && runAttemptCount < 3) Result.retry() else Result.failure()
        }
        catch (_: Exception) { store.update { it.copy(status = "failed") }; Result.failure() }
    }
}
