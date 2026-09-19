package com.gernalix.personalhub

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HealthCanonicalSmokeDeviceTest {
    @Test fun syntheticCanonicalTimelineExamsSamplesAndJournalOpenOnQaAvd() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".qa"))
        val db=PersonalHubDatabase.get(context).openHelper.writableDatabase
        val suffix=UUID.randomUUID().toString()
        val batch="qa-health-$suffix";val event="qa-event-$suffix";val sample="qa-sample-$suffix"
        val exam="qa-exam-$suffix";val measure="qa-measure-$suffix";val journalEvent="qa-journal-event-$suffix";val journal="qa-journal-$suffix"
        val now=System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.execSQL("INSERT INTO health_import_batches(id,received_at_ms,imported_at_ms,source_system,author) VALUES(?,?,?,'synthetic','qa')",arrayOf(batch,now,now))
            db.execSQL("INSERT INTO health_events(id,import_batch_id,event_kind,occurred_at_ms,time_precision,title_it,source_system,created_at_ms,updated_at_ms) VALUES(?,?,'sample',?,'datetime','Prelievo QA','synthetic',?,?)",arrayOf(event,batch,now,now,now))
            db.execSQL("INSERT INTO health_samples(id,event_id,sample_kind) VALUES(?,?,'blood')",arrayOf(sample,event))
            db.execSQL("INSERT INTO health_examinations(id,canonical_name,display_name_it,category_it) VALUES(?,?,'Misura QA','Test')",arrayOf(exam,exam))
            db.execSQL("INSERT INTO health_measurements(id,sample_id,examination_id,numeric_value,received_at_ms,availability_basis) VALUES(?,?,?,8.9,?,'chat_received_proxy')",arrayOf(measure,sample,exam,now+3_600_000))
            db.execSQL("INSERT INTO health_events(id,import_batch_id,event_kind,occurred_at_ms,time_precision,title_it,source_system,created_at_ms,updated_at_ms) VALUES(?,?,'journal',?,'datetime','Diario QA','synthetic',?,?)",arrayOf(journalEvent,batch,now,now,now))
            db.execSQL("INSERT INTO health_journal_entries(id,event_id,title_it,text_it,original_text_da) VALUES(?,?,'Diario QA','Nota clinica sintetica','Syntetisk note')",arrayOf(journal,journalEvent))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        try {
            val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            InstrumentationRegistry.getInstrumentation().startActivitySync(Intent().setClassName(context.packageName,"com.gernalix.personalhub.salute.SaluteActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertNotNull(device.wait(Until.findObject(By.text(context.getString(com.gernalix.personalhub.salute.R.string.health_title))),10_000))
            assertNotNull(device.wait(Until.findObject(By.text("Misura QA")),10_000))
            device.findObject(By.text(context.getString(com.gernalix.personalhub.salute.R.string.health_exams))).click()
            assertNotNull(device.wait(Until.findObject(By.text("Misura QA")),5_000))
            device.findObject(By.text(context.getString(com.gernalix.personalhub.salute.R.string.health_samples))).click()
            assertNotNull(device.wait(Until.findObject(By.text("Prelievo QA")),5_000))
            device.findObject(By.text(context.getString(com.gernalix.personalhub.salute.R.string.health_journal))).click()
            assertNotNull(device.wait(Until.findObject(By.text("Diario QA")),5_000))
            device.findObject(By.text("Diario QA")).click()
            assertNotNull(device.wait(Until.findObject(By.text(context.getString(com.gernalix.personalhub.salute.R.string.health_clinical_note))),5_000))
            assertTrue(db.query("PRAGMA foreign_key_check").use { !it.moveToFirst() })
        } finally {
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM health_journal_entries WHERE id=?",arrayOf(journal))
                db.execSQL("DELETE FROM health_events WHERE id=?",arrayOf(journalEvent))
                db.execSQL("DELETE FROM health_measurements WHERE id=?",arrayOf(measure))
                db.execSQL("DELETE FROM health_examinations WHERE id=?",arrayOf(exam))
                db.execSQL("DELETE FROM health_samples WHERE id=?",arrayOf(sample))
                db.execSQL("DELETE FROM health_events WHERE id=?",arrayOf(event))
                db.execSQL("DELETE FROM health_import_batches WHERE id=?",arrayOf(batch))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }
}
