package com.gernalix.personalhub

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.wordpulse.app.data.PvtResultEntity
import com.wordpulse.app.data.SystemTimeProvider
import com.wordpulse.app.data.WordRepository
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WordPulsePersistenceDeviceTest {
    @TableProbe("word_entries", "wordpulse_sessions", "app_state", "correction_events", "pvt_results")
    @Test fun wordSessionCorrectionAndPvtPersistThroughRepository() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "com.gernalix.personalhub.qa")
        val name = "qa914263-word-${UUID.randomUUID()}.db"
        val owner = PersonalHubDatabase.openTemporary(context, name)
        try {
            val repo = WordRepository(owner, SystemTimeProvider)
            val session = repo.startNewSession()
            val dao = owner.wordPulseDao()
            assertNotNull(dao.getSession(session.id))
            owner.openHelper.readableDatabase.query("SELECT current_session_id FROM app_state WHERE id=1").use {
                assertEquals(true, it.moveToFirst()); assertEquals(session.id, it.getString(0))
            }
            val submitted = repo.submitWord("qapersistence", session.id)
            assertEquals("qapersistence", dao.getWordById(submitted.insertedEntryId)?.originalWord)
            repo.saveFatigueScore(submitted.insertedEntryId, 42)
            assertEquals(42, dao.getWordById(submitted.insertedEntryId)?.fatigueScore)
            val correction = repo.correctSubmittedWord(
                submitted.insertedEntryId, submitted.originalWord, submitted.normalizedWord,
                submitted.sessionId, submitted.submittedAtUtcMs,
            )
            assertNotNull(correction)
            assertEquals(1, dao.getCorrectionEvents().size)
            assertEquals(null, dao.getWordById(submitted.insertedEntryId))
            val second = repo.submitWord("qareadback", session.id)
            assertNotNull(dao.getWordById(second.insertedEntryId))
            val now = System.currentTimeMillis()
            val pvtId = repo.savePvtResult(PvtResultEntity(
                startedAtUtcMs = now - 2_000, completedAtUtcMs = now,
                durationMs = 2_000, trialCount = 2, medianReactionTimeMs = 250.0,
                p90ReactionTimeMs = 280.0, lapseCount = 0, falseStartCount = 0,
            ))
            assertEquals(pvtId, dao.getPvtResults().single().id)
            owner.openHelper.readableDatabase.query("SELECT count(*) FROM wordpulse_sessions WHERE id=?", arrayOf(session.id)).use {
                it.moveToFirst(); assertEquals(1L, it.getLong(0))
            }
        } finally {
            owner.close()
            context.deleteDatabase(name)
        }
    }
}
