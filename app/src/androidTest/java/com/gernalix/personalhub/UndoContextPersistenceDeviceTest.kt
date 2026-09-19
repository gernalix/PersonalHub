package com.gernalix.personalhub

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gernalix.personalhub.core.database.HubActivityStatus
import com.gernalix.personalhub.core.database.HubActivityUndoEngine
import com.gernalix.personalhub.core.database.HubActivityUndoResult
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceAccount
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceCapsule
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UndoContextPersistenceDeviceTest {
    /** Transient transaction context: the undo writer materializes it and the capture trigger reads it before cleanup. */
    @TableProbe("hub_activity_undo_context")
    @Test fun undoContextLinksCompensatingActivityAndClearsTransientRow() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "com.gernalix.personalhub.qa")
        val name = "qa914263-undo-${UUID.randomUUID()}.db"
        val owner = PersonalHubDatabase.openTemporary(context, name)
        try {
            val accountId = UUID.randomUUID().toString()
            FinanceCapsule(owner).saveAccount(FinanceAccount(id = accountId, name = "QA914263Undo", currency = "DKK"))
            val original = owner.activityDao().page("soldi", 1, null, null, 20)
                .single { it.sourceTable == "finance_accounts" && it.entityId == accountId }
            assertTrue(original.reversible)
            assertTrue(HubActivityUndoEngine.undo(owner, original.id) is HubActivityUndoResult.Success)
            assertEquals(HubActivityStatus.REVERTED, owner.activityDao().byId(original.id)?.status)
            val compensation = owner.activityDao().page("soldi", 1, null, null, 20)
                .single { it.revertsActivityId == original.id }
            assertEquals(original.id, compensation.revertsActivityId)
            owner.openHelper.readableDatabase.query("SELECT count(*) FROM hub_activity_undo_context").use {
                it.moveToFirst(); assertEquals(0L, it.getLong(0))
            }
            assertEquals(null, owner.financeDao().account(accountId))
        } finally {
            owner.close()
            context.deleteDatabase(name)
        }
    }
}
