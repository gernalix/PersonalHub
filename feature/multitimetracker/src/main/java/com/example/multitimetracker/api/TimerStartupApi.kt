package com.example.multitimetracker.api

import com.example.multitimetracker.perf.StartupPerfTrace

/** Public host entrypoint for Timer startup instrumentation. */
object TimerStartupApi {
    fun applicationOnCreate() = StartupPerfTrace.applicationOnCreate()
}
