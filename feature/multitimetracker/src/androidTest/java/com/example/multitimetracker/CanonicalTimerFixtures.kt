package com.example.multitimetracker

import androidx.test.platform.app.InstrumentationRegistry
import com.example.multitimetracker.model.SessionUi
import com.gernalix.personalhub.core.database.PersonalHubDatabase

/** UI callback fixtures still represent persisted, canonically registered sessions. */
internal fun registerCanonicalTimerSessionFixture(session: SessionUi) {
    if (session.id <= 0) return
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    check(context.packageName == "com.example.multitimetracker.test")
    PersonalHubDatabase.get(context).openHelper.writableDatabase.execSQL(
        "INSERT OR IGNORE INTO sessions(id,title,start_ms,end_ms,expected_end_ms,created_at_ms,updated_at_ms,deleted_at_ms) VALUES(?,?,?,?,?,1,1,?)",
        arrayOf(session.id, session.title, session.startMs, session.endMs, session.expectedEndMs, session.deletedAtMs),
    )
}
