package com.gernalix.personalhub.soldi.capsules.playsubscriptions

import com.gernalix.personalhub.soldi.FinanceReminderScheduler
import com.gernalix.personalhub.soldi.PlaySubscriptionWorker
import android.content.Context
import androidx.work.*
import androidx.work.ListenableWorker.Result
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class PlayAuthorizationNeeded : Exception()
internal class PlayApiFailure(val statusCode: Int) : Exception()
internal object PlayAuthorization {
    const val SCOPE = "https://www.googleapis.com/auth/dataportability.play.subscriptions"
    // Tokens stay in Google Play services / transient memory. No client secret or vault token in APK.
    suspend fun request(context: Context): AuthorizationResult = suspendCancellableCoroutine { continuation ->
        val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE)))
            .setOptOutIncludingGrantedScopes(true).build()
        Identity.getAuthorizationClient(context).authorize(request)
            .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
            .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(PlayAuthorizationNeeded()) }
    }
}

/** Full snapshots, not time filters: subscription changes may refer to subscriptions started years ago. */
internal class PlayPortabilityClient(
    private val context: Context,
    private val transport: PlayArchiveTransport = GooglePlayArchiveTransport(),
    private val now: () -> Instant = Instant::now,
) {
    private val store = PlaySubscriptionStore(context)
    suspend fun sync(token: String): PlayCatalog = SYNC.withLock {
        withContext(Dispatchers.IO) {
            var catalog = store.read()
            if (catalog.job == null) {
                val elapsed = catalog.initiatedAt?.let { Duration.between(it, now()) }
                if (elapsed != null && elapsed < Duration.ofHours(24)) return@withContext catalog
                val response = transport.request("portabilityArchive:initiate", token, "{\"resources\":[\"play.subscriptions\"]}")
                val job = response.getString("archiveJobId")
                require(Regex("[A-Za-z0-9_-]{1,200}").matches(job))
                catalog = store.update { it.copy(job = job, initiatedAt = now(), accessType = response.text("accessType"), status = "pending") }
            }
            val state = try { transport.request("archiveJobs/${catalog.job}/portabilityArchiveState", token) }
            catch (error: PlayApiFailure) {
                if (error.statusCode != 404) throw error
                return@withContext store.update { it.copy(job = null, status = "failed") }
            }
            when (state.getString("state")) {
                "COMPLETE" -> {
                    val urls = state.getJSONArray("urls")
                    require(urls.length() in 1..20)
                    val rows = (0 until urls.length()).flatMap { index -> transport.download(urls.getString(index)) }
                    store.import(rows, now(), fullSnapshot = true)
                    catalog = store.update { it.copy(job = null, status = "complete", automatic = it.automatic && it.accessType == "ACCESS_TYPE_TIME_BASED") }
                    FinanceReminderScheduler.reschedule(context)
                }
                "IN_PROGRESS" -> catalog = store.update { it.copy(status = "pending") }
                "FAILED", "CANCELLED" -> catalog = store.update { it.copy(job = null, status = "failed") }
                else -> throw IOException("Unexpected archive state")
            }
            catalog
        }
    }
    companion object { private val SYNC = Mutex() }
}

internal interface PlayArchiveTransport {
    fun request(path: String, token: String, body: String? = null): JSONObject
    fun download(rawUrl: String): List<PlaySubscription>
}
internal class GooglePlayArchiveTransport : PlayArchiveTransport {
    override fun request(path: String, token: String, body: String?): JSONObject {
        val connection = URI("https://dataportability.googleapis.com/v1/$path").toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20000; connection.readTimeout = 30000; connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            if (connection.responseCode !in 200..299) throw PlayApiFailure(connection.responseCode)
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val size = input.read(buffer); if (size < 0) break
                    require(output.size() + size <= 1024 * 1024)
                    output.write(buffer, 0, size)
                }
                output.toByteArray()
            }
            require(bytes.size <= 1024 * 1024)
            return JSONObject(bytes.toString(Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
    override fun download(rawUrl: String): List<PlaySubscription> {
        val uri = URI(rawUrl)
        require(uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
            (uri.host == "storage.googleapis.com" || uri.host?.endsWith(".storage.googleapis.com") == true)) { "Archive origin not allowed" }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false; connection.connectTimeout = 20000; connection.readTimeout = 60000
            if (connection.responseCode !in 200..299) throw PlayApiFailure(connection.responseCode)
            // Signed URLs are bearer capabilities: never log or store them; never attach OAuth headers here.
            return connection.inputStream.use(PlaySubscriptionJson::read)
        } finally { connection.disconnect() }
    }
}

internal object PlaySubscriptionWork {
    private const val DAILY = "soldi-play-daily"
    private const val POLL = "soldi-play-poll"
    fun schedule(context: Context, catalog: PlayCatalog) {
        val work = WorkManager.getInstance(context)
        if (catalog.automatic) work.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PlaySubscriptionWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        else work.cancelUniqueWork(DAILY)
        if (catalog.job != null) work.enqueueUniqueWork(POLL, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PlaySubscriptionWorker>().setInitialDelay(15, TimeUnit.MINUTES).setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        else work.cancelUniqueWork(POLL)
    }
}

internal suspend fun runPlaySubscriptionWork(context: Context, runAttemptCount: Int): ListenableWorker.Result {
    val applicationContext = context.applicationContext
    val store = PlaySubscriptionStore(applicationContext)
    return try {
        val catalog = withContext(Dispatchers.IO) { store.read() }
        if (!catalog.automatic && catalog.job == null) return Result.success()
        val auth = PlayAuthorization.request(applicationContext)
        if (auth.hasResolution() || auth.accessToken.isNullOrBlank()) throw PlayAuthorizationNeeded()
        val updated = PlayPortabilityClient(applicationContext).sync(auth.accessToken!!)
        if (!updated.automatic) WorkManager.getInstance(applicationContext).cancelUniqueWork("soldi-play-daily")
        if (updated.job != null) Result.retry() else Result.success()
    } catch (_: PlayAuthorizationNeeded) {
        withContext(Dispatchers.IO) { store.update { it.copy(status = "authorization") } }
        Result.success()
    } catch (error: PlayApiFailure) {
        withContext(Dispatchers.IO) { store.update { it.copy(status = if (error.statusCode in listOf(401, 403)) "authorization" else "failed") } }
        if (error.statusCode == 429 || error.statusCode >= 500) Result.retry() else Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        withContext(Dispatchers.IO) { store.update { it.copy(status = "failed") } }
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
