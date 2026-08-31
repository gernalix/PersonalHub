package com.example.multitimetracker

internal class SingleSubmitGuard(
    private val ttlMs: Long = 1_500L,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val acceptedAtByKey = LinkedHashMap<String, Long>()

    fun tryAccept(key: String): Boolean = synchronized(acceptedAtByKey) {
        val now = nowMs()
        val cutoff = now - ttlMs
        val iterator = acceptedAtByKey.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value < cutoff) iterator.remove()
        }
        val lastAcceptedAt = acceptedAtByKey[key]
        if (lastAcceptedAt != null && now - lastAcceptedAt < ttlMs) return@synchronized false
        acceptedAtByKey[key] = now
        true
    }
}
