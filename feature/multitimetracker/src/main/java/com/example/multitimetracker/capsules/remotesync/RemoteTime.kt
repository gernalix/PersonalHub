package com.example.multitimetracker.capsules.remotesync

import java.time.Instant
import java.time.format.DateTimeFormatter

internal object RemoteTime {
    fun utcZ(epochMs: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMs))
}
