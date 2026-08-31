package com.example.multitimetracker.util

/**
 * Compile-time guardrail for persistence write APIs.
 *
 * Any call site that performs a write to the authoritative persistence layer should
 * opt-in explicitly so cross-capsule write paths remain visible during refactors.
 *
 * Level is WARNING (not ERROR) to avoid breaking existing builds; the intent is to
 * make write paths explicit and reviewable.
 */
@RequiresOptIn(
    message = "Persistence write API: calls should go through the owning capsule/bridge, or opt-in explicitly.",
    level = RequiresOptIn.Level.WARNING
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY
)
annotation class CapsuleWriteApi
