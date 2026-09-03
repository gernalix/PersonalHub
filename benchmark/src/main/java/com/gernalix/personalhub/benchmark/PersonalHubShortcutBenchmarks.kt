package com.gernalix.personalhub.benchmark

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TARGET_PACKAGE_ARGUMENT = "targetPackage"
private const val DEFAULT_ISOLATED_PACKAGE = "com.gernalix.personalhub.benchmarktarget"
private val targetPackageName: String
    get() = InstrumentationRegistry.getArguments()
        .getString(TARGET_PACKAGE_ARGUMENT)
        ?.takeIf { it.isNotBlank() }
        ?: DEFAULT_ISOLATED_PACKAGE

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
            packageName = targetPackageName,
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
            packageName = targetPackageName,
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
        .setComponent(ComponentName(targetPackageName, module.aliasName))
        .setPackage(targetPackageName)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
