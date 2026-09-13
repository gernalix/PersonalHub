package com.wordpulse.app.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.wordpulse.app.domain.SleepContext
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

enum class HealthConnectAvailability {
    Available,
    ProviderUpdateRequired,
    Unavailable,
}

data class SleepIntegrationState(
    val availability: HealthConnectAvailability = HealthConnectAvailability.Unavailable,
    val permissionGranted: Boolean = false,
    val sleepContext: SleepContext? = null,
    val error: String? = null,
)

interface SleepContextSource {
    val requiredPermissions: Set<String>

    fun availability(): HealthConnectAvailability

    suspend fun hasPermissions(): Boolean

    suspend fun readSleepContext(nowUtcMs: Long): SleepContext?
}

object NoSleepContextSource : SleepContextSource {
    override val requiredPermissions: Set<String> = emptySet()

    override fun availability(): HealthConnectAvailability = HealthConnectAvailability.Unavailable

    override suspend fun hasPermissions(): Boolean = false

    override suspend fun readSleepContext(nowUtcMs: Long): SleepContext? = null
}

class HealthConnectSleepSource(context: Context) : SleepContextSource {
    private val appContext = context.applicationContext

    override val requiredPermissions: Set<String> =
        setOf(HealthPermission.getReadPermission(SleepSessionRecord::class))

    override fun availability(): HealthConnectAvailability =
        when (HealthConnectClient.getSdkStatus(appContext)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.Available
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                HealthConnectAvailability.ProviderUpdateRequired
            else -> HealthConnectAvailability.Unavailable
        }

    override suspend fun hasPermissions(): Boolean {
        if (availability() != HealthConnectAvailability.Available) return false
        return runCatching {
            client().permissionController.getGrantedPermissions().containsAll(requiredPermissions)
        }.getOrDefault(false)
    }

    override suspend fun readSleepContext(nowUtcMs: Long): SleepContext? {
        if (!hasPermissions()) return null
        val now = Instant.ofEpochMilli(nowUtcMs)
        val start = now.minus(LOOKBACK_DAYS, ChronoUnit.DAYS)
        val response = client().readRecords(
            ReadRecordsRequest<SleepSessionRecord>(
                timeRangeFilter = TimeRangeFilter.between(start, now),
                ascendingOrder = false,
                pageSize = MAX_SLEEP_SESSIONS,
            ),
        )
        val sessions = response.records
            .filter { !it.endTime.isAfter(now) && it.endTime.isAfter(it.startTime) }
            .sortedByDescending { it.endTime }
        val latest = sessions.firstOrNull() ?: return null
        val durations = sessions.map { record ->
            Duration.between(record.startTime, record.endTime).toMillis()
        }.filter { it > 0L }.sorted()
        return SleepContext(
            lastWakeUtcMs = latest.endTime.toEpochMilli(),
            lastSleepDurationMs = Duration.between(latest.startTime, latest.endTime).toMillis(),
            personalMedianSleepDurationMs = durations.medianOrNull(),
        )
    }

    private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(appContext)

    private fun List<Long>.medianOrNull(): Long? {
        if (isEmpty()) return null
        val middle = size / 2
        return if (size % 2 == 0) {
            ((this[middle - 1].toDouble() + this[middle]) / 2.0).toLong()
        } else {
            this[middle]
        }
    }

    private companion object {
        const val LOOKBACK_DAYS = 30L
        const val MAX_SLEEP_SESSIONS = 200
    }
}
