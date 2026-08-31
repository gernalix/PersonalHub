package com.wordpulse.app.data

interface TimeProvider {
    fun nowUtcMs(): Long
}

object SystemTimeProvider : TimeProvider {
    override fun nowUtcMs(): Long = System.currentTimeMillis()
}
