package com.gernalix.personalhub

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import com.gernalix.personalhub.core.hubcontext.HubTemporalQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=PersonalHubApplication::class)
class HealthHubIntegrationTest {
    private val context: Context=ApplicationProvider.getApplicationContext()
    @Before fun setUp() { PersonalHubDatabase.closeInstance();context.deleteDatabase(PersonalHubDatabase.DATABASE_NAME) }
    @After fun tearDown() { PersonalHubDatabase.closeInstance();context.deleteDatabase(PersonalHubDatabase.DATABASE_NAME) }
    @Test fun allFourHealthKindsResolveAndEventAppearsInTemporalSearch()=runBlocking(Dispatchers.IO) {
        val db=PersonalHubDatabase.get(context).openHelper.writableDatabase
        val now=System.currentTimeMillis()
        db.execSQL("INSERT INTO health_import_batches(id,received_at_ms,imported_at_ms,source_system,author) VALUES('batch',?,?, 'synthetic','chatgpt')",arrayOf(now,now))
        db.execSQL("INSERT INTO health_events(id,import_batch_id,event_kind,occurred_at_ms,time_precision,title_it,source_system,created_at_ms,updated_at_ms) VALUES('event','batch','sample',?,'datetime','Prelievo sintetico','synthetic',?,?)",arrayOf(now,now,now))
        db.execSQL("INSERT INTO health_samples(id,event_id,sample_kind) VALUES('sample','event','blood')")
        db.execSQL("INSERT INTO health_examinations(id,canonical_name,display_name_it,category_it) VALUES('exam','synthetic','Esame sintetico','Test')")
        db.execSQL("INSERT INTO health_measurements(id,sample_id,examination_id,numeric_value,availability_basis) VALUES('measurement','sample','exam',8.9,'unknown')")
        db.execSQL("INSERT INTO health_events(id,import_batch_id,event_kind,occurred_at_ms,time_precision,title_it,source_system,created_at_ms,updated_at_ms) VALUES('journal-event','batch','journal',?,'datetime','Diario sintetico','synthetic',?,?)",arrayOf(now,now,now))
        db.execSQL("INSERT INTO health_journal_entries(id,event_id,text_it,original_text_da) VALUES('journal','journal-event','Nota sintetica','Syntetisk note')")
        for((kind,id) in listOf("event" to "event","sample" to "sample","measurement" to "measurement","journal" to "journal")) {
            val adapter=HubContextRuntime.adapter("salute",kind)
            assertTrue(adapter.exists(id))
            assertNotNull(adapter.summaries(setOf(id))[id])
        }
        val temporal=HubContextRuntime.temporal(HubTemporalQuery(now-1,now+1),setOf("salute"))
        assertTrue(temporal.any { it.stableId=="event" && it.title=="Prelievo sintetico" })
        val id=HubContextRuntime.createContext(listOf(HubEntityRef("salute","event","event") to "anchor",HubEntityRef("salute","sample","sample") to "participant"),title="Health synthetic")
        assertEquals(2,requireNotNull(HubContextRuntime.context(id)).members.size)
    }
}
