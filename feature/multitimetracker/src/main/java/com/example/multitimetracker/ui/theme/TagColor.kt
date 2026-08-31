package com.example.multitimetracker.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs

/**
 * Precision Tool: deterministic color roles derived from a string.
 *
 * Philosophy:
 * - Color is information, not decoration.
 * - Medium-low saturation.
 * - Balanced lightness.
 * - No random rainbow palette.
 */
@Immutable
data class PrecisionColorRoles(
    /** Primary stroke/rail color (used for left vertical rail, timeline segments, etc.) */
    val rail: Color,
    /** Very subtle fill usable for pressed/selected surfaces (alpha already baked in). */
    val subtleFill: Color,
    /** More subtle fill (for hover-like or secondary emphasis). */
    val ultraSubtleFill: Color,
    /** Text color to use when placed ON TOP of rail (rare). */
    val onRail: Color
)

/**
 * Deterministic hue from a seed using the golden ratio conjugate.
 * This avoids visible clustering that you often get with `hashCode() % 360`.
 */
private fun hueFromSeed(seed: String, namespace: Int = 0): Float {
    val h = (seed.hashCode() xor (namespace * 31)).toLong() and 0xFFFF_FFFFL
    val u01 = (h.toDouble() / 0x1_0000_0000L.toDouble()).toFloat() // 0..1
    val phi = 0.6180339887f
    val x = (u01 * phi) % 1f
    return x * 360f
}

private fun clamp01(x: Float) = x.coerceIn(0f, 1f)

/**
 * Core generator.
 *
 * - Saturation is intentionally capped.
 * - Lightness is stable and theme-aware.
 */
fun precisionColorRolesFromSeed(seed: String, isDarkTheme: Boolean, namespace: Int = 0): PrecisionColorRoles {
    val hue = hueFromSeed(seed, namespace)

    // Medium-low saturation.
    val baseS = 0.46f
    val sJitter = ((abs(seed.hashCode()) % 13) - 6) / 100f // -0.06 .. +0.06
    val s = clamp01(baseS + sJitter)

    // Balanced lightness. Dark theme needs brighter rails to remain readable.
    val baseL = if (isDarkTheme) 0.62f else 0.48f
    val lJitter = ((abs(seed.reversed().hashCode()) % 11) - 5) / 100f // -0.05 .. +0.05
    val l = clamp01(baseL + lJitter)

    val rail = Color.hsl(hue, s, l)

    // Baked alphas for surfaces: very subtle, never aggressive.
    val subtleFill = rail.copy(alpha = if (isDarkTheme) 0.16f else 0.12f)
    val ultraSubtleFill = rail.copy(alpha = if (isDarkTheme) 0.10f else 0.07f)

    // On-rail text: we rarely place text on colored areas, but keep it predictable.
    val onRail = if (isDarkTheme) Color.White else Color.Black

    return PrecisionColorRoles(
        rail = rail,
        subtleFill = subtleFill,
        ultraSubtleFill = ultraSubtleFill,
        onRail = onRail
    )
}

/**
 * Task color: derived from the dominant tag name.
 * If you want a “dominant” rule beyond the first tag, implement it in the caller.
 */
fun taskRolesFromTagName(tagName: String, isDarkTheme: Boolean): PrecisionColorRoles =
    precisionColorRolesFromSeed(tagName, isDarkTheme, namespace = 1)

/** Chain color: derived from chain name (separate namespace to avoid collisions with tags). */
fun chainRolesFromName(chainName: String, isDarkTheme: Boolean): PrecisionColorRoles =
    precisionColorRolesFromSeed(chainName, isDarkTheme, namespace = 2)

/**
 * Alert color: controlled hue band (distinct but not screaming).
 * We still derive a slight variation from seed to avoid “everything same color”.
 */
fun alertRolesFromSeed(seed: String, isDarkTheme: Boolean): PrecisionColorRoles {
    val base = precisionColorRolesFromSeed(seed, isDarkTheme, namespace = 3)

    // Nudge towards a controlled band: amber/orange (technical warning), not full red.
    val targetHue = if (isDarkTheme) Color.hsl(28f, 0.52f, 0.62f) else Color.hsl(28f, 0.50f, 0.50f)
    val rail = lerp(base.rail, targetHue, 0.55f)

    return base.copy(
        rail = rail,
        subtleFill = rail.copy(alpha = if (isDarkTheme) 0.16f else 0.12f),
        ultraSubtleFill = rail.copy(alpha = if (isDarkTheme) 0.10f else 0.07f)
    )
}
