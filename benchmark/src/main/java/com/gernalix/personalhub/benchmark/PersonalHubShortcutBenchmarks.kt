package com.gernalix.personalhub.benchmark

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE_NAME = "com.gernalix.personalhub"

private enum class ShortcutModule(val aliasName: String) {
    PEOPLE("com.gernalix.personalhub.shortcut.PeopleShortcutActivity"),
    TIMER("com.gernalix.personalhub.shortcut.TimerShortcutActivity"),
    PLACES("com.gernalix.personalhub.shortcut.PlacesShortcutActivity"),
    SUBSTANCES("com.gernalix.personalhub.shortcut.SubstancesShortcutActivity"),
    WORDPULSE("com.gernalix.personalhub.shortcut.WordPulseShortcutActivity"),
}

@RunWith(AndroidJUnit4::class)
class PersonalHubShortcutBenchmarks {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test fun peopleColdStartup() = coldStartup(ShortcutModule.PEOPLE)
    @Test fun timerColdStartup() = coldStartup(ShortcutModule.TIMER)
    @Test fun placesColdStartup() = coldStartup(ShortcutModule.PLACES)
    @Test fun substancesColdStartup() = coldStartup(ShortcutModule.SUBSTANCES)
    @Test fun wordPulseColdStartup() = coldStartup(ShortcutModule.WORDPULSE)

    private fun coldStartup(module: ShortcutModule) {
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.COLD,
            iterations = 5,
            setupBlock = {
                pressHome()
            },
        ) {
            startActivityAndWait(shortcutIntent(module))
        }
    }
}

@RunWith(AndroidJUnit4::class)
class PersonalHubBaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun shortcutStartupProfiles() {
        baselineProfileRule.collect(
            packageName = PACKAGE_NAME,
            includeInStartupProfile = true,
            outputFilePrefix = "personalhub-shortcuts",
        ) {
            ShortcutModule.entries.forEach { module ->
                pressHome()
                startActivityAndWait(shortcutIntent(module))
                device.waitForIdle()
            }
        }
    }
}

private fun shortcutIntent(module: ShortcutModule): Intent =
    Intent(Intent.ACTION_VIEW)
        .setComponent(ComponentName(PACKAGE_NAME, module.aliasName))
        .setPackage(PACKAGE_NAME)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
