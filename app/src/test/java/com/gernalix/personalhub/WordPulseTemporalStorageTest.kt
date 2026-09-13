package com.gernalix.personalhub

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.hubcontext.HubTemporalQuery
import com.wordpulse.app.data.WordEntry
import com.wordpulse.app.data.WordSession
import com.wordpulse.app.domain.TypingAnomalyDetector
import com.wordpulse.app.domain.TypingBaselineCalculator
import com.wordpulse.app.domain.TypingPerformanceSample
import com.wordpulse.app.hub.WordSessionHubAdapter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WordPulseTemporalStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @Before fun setUp() { PersonalHubDatabase.resetForTests(); context.deleteDatabase(PersonalHubDatabase.DB_NAME) }
    @After fun tearDown() { PersonalHubDatabase.resetForTests(); context.deleteDatabase(PersonalHubDatabase.DB_NAME) }

    @Test fun providerExposesOnlyRealPersistedFatigueAtSessionGrain() = runBlocking {
        val db = PersonalHubDatabase.get(context)
        val dao = db.wordPulseDao()
        dao.insertSession(WordSession("valid", 1_000, 2_000))
        dao.insertSession(WordSession("insufficient", 3_000, 4_000))
        val history = listOf(sample(1, 100.0), sample(2, 110.0))
        val detector = TypingAnomalyDetector(TypingBaselineCalculator(minimumSampleCount = 2))
        val derived = detector.evaluate(sample(3, 180.0), history).fatigueScore
        assertNotNull(derived)
        dao.insertWord(entry("valid", 1_500, derived))
        val unavailable = TypingAnomalyDetector().evaluate(sample(4, 180.0), history).fatigueScore
        assertNull(unavailable)
        dao.insertWord(entry("insufficient", 3_500, unavailable))

        val records = WordSessionHubAdapter(context).queryTemporal(HubTemporalQuery(0, 5_000, 10)).records
        assertEquals(derived.toString(), records.single { it.stableId == "valid" }.attributes["fatigueScore"])
        assertFalse(records.single { it.stableId == "insufficient" }.attributes.containsKey("fatigueScore"))
    }

    private fun sample(id: Long, duration: Double) = TypingPerformanceSample(
        entryId = id, finalCharacterCount = 5, charactersPerMinute = 300.0,
        durationPerCharacterMs = duration, correctionActionsPerCharacter = 0.0,
        deletedCharactersPerCharacter = 0.0, longestInterKeyPauseMs = duration,
        interKeyIntervalVariabilityMs = duration / 4, invalidInputAttemptCount = 0.0,
    )

    private fun entry(session: String, at: Long, fatigue: Int?) = WordEntry(
        originalWord = "test", normalizedWord = "test", createdAtUtcMs = at,
        sessionId = session, fatigueScore = fatigue,
    )
}
