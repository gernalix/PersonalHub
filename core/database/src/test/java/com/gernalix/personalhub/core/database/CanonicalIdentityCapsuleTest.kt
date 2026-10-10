package com.gernalix.personalhub.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.contracts.database.HubExternalIdentity
import com.gernalix.personalhub.core.database.capsules.identity.CanonicalIdentityCapsule
import com.gernalix.personalhub.core.database.capsules.mutationevents.MutationEventStore
import com.gernalix.sostanze.data.SubstanceEntity
import com.supercontacts.app.data.local.ContactEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CanonicalIdentityCapsuleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database get() = PersonalHubDatabase.get(context)
    @Before fun reset() { PersonalHubDatabase.closeInstance(); context.deleteDatabase(PersonalHubDatabase.DB_NAME) }
    @After fun cleanup() { PersonalHubDatabase.closeInstance(); context.deleteDatabase(PersonalHubDatabase.DB_NAME) }

    @Test fun roomCreationRegistersUuidAndMutationCarriesCanonicalReference(): Unit = runBlocking {
        val id = database.dao().insertSubstance(SubstanceEntity(name="Fixture",canonicalName="fixture",type="farmaco",stockCurrent=1.0,stockUnit="mg",dosePerIntake=1.0,doseUnit="mg",dailyFrequency=1,startEpochDay=1))
        val identity = CanonicalIdentityCapsule(context).canonicalId("substances", id)
        assertEquals(4,java.util.UUID.fromString(identity).version())
        assertEquals(id.toString(),CanonicalIdentityCapsule(context).localKey("substances/substance",identity))
        val event = MutationEventStore.recent(database.openHelper.readableDatabase).single { it.eventType == "substances.substance.created" }
        assertEquals("substances/substance",event.entityKind)
        assertEquals(identity,event.canonicalId)
        database.dao().updateSubstance(requireNotNull(database.dao().substanceById(id)).copy(name="Changed"))
        assertEquals(identity,CanonicalIdentityCapsule(context).canonicalId("substances", id))
        val backup=DatabaseVault.backupCurrent(context)
        try { DatabaseVault.validate(context,backup) } finally { backup.delete() }
    }

    @Test fun explicitExternalTupleIsIdempotentAndConflictsFail(): Unit = runBlocking {
        database.contactsDao().insertContact(ContactEntity(publicId="person-a",createdAt=1,updatedAt=1))
        database.contactsDao().insertContact(ContactEntity(publicId="person-b",createdAt=1,updatedAt=1))
        val capsule=CanonicalIdentityCapsule(context)
        val identity=HubExternalIdentity(canonicalId="person-a",entityKind="people/person",system="fixture",sourceScope="account",externalId="native",linkMethod="explicit",createdAt=1,updatedAt=1)
        val id=capsule.linkExternal(identity)
        assertEquals(id,capsule.linkExternal(identity.copy(id=java.util.UUID.randomUUID().toString())))
        assertEquals("person-a",capsule.resolveExternal("people/person","fixture","account","native")?.canonicalId)
        assertThrows(IllegalArgumentException::class.java) { capsule.linkExternal(identity.copy(canonicalId="person-b")) }
    }

    @Test fun aliasResolutionSurvivesReopenAndLosingIdsRemainReserved(): Unit = runBlocking {
        database.contactsDao().insertContact(ContactEntity(publicId="person-a",createdAt=1,updatedAt=1))
        database.contactsDao().insertContact(ContactEntity(publicId="person-b",createdAt=1,updatedAt=1))
        database.openHelper.writableDatabase.execSQL("INSERT INTO hub_entity_aliases VALUES('person-a','person-b','explicit test merge',1)")
        PersonalHubDatabase.closeInstance()
        assertEquals("person-b",CanonicalIdentityCapsule(context).resolve("people/person","person-a")?.canonicalId)
        assertNull(CanonicalIdentityCapsule(context).resolve("places/place","person-a"))
        assertThrows(android.database.sqlite.SQLiteException::class.java) { database.openHelper.writableDatabase.execSQL("INSERT INTO hub_entity_aliases VALUES('person-b','person-a',NULL,1)") }
    }

    @Test fun macroUpsertRetainsIdentityAndReturnsTheOriginalLocalKey(): Unit = runBlocking {
        val dao=database.dao()
        val id=dao.upsertMacro(com.gernalix.sostanze.data.MacroEntity(name="Original"))
        val canonical=CanonicalIdentityCapsule(context).canonicalId("macros",id)
        assertEquals(id,dao.upsertMacro(com.gernalix.sostanze.data.MacroEntity(id=id,name="Edited through legacy constructor")))
        assertEquals(canonical,CanonicalIdentityCapsule(context).canonicalId("macros",id))
    }

    @Test fun timerSnapshotRulesUseRegistryIdsWithoutChangingSnapshotStorage() {
        val db=database.openHelper.writableDatabase
        val json="""{"timeFenceRules":[{"id":42,"isDeleted":false}]}"""
        db.beginTransaction()
        try { CanonicalIdentityCapsule.synchronizeTimerAlertIdentities(db,json);db.execSQL("INSERT INTO snapshot(id,json,saved_at_ms) VALUES(1,?,1)",arrayOf(json));db.setTransactionSuccessful() } finally { db.endTransaction() }
        val original=CanonicalIdentityCapsule(context).canonicalId("snapshot.timeFenceRules",42)
        CanonicalIdentityCapsule.synchronizeTimerAlertIdentities(db,json)
        assertEquals(original,CanonicalIdentityCapsule(context).canonicalId("snapshot.timeFenceRules",42))
        assertEquals("42",CanonicalIdentityCapsule(context).localKey("timer/alert",original))
        CanonicalIdentityCapsule.synchronizeTimerAlertIdentities(db,"""{"timeFenceRules":[]} """)
        assertNull(CanonicalIdentityCapsule(context).localKey("timer/alert",original))
    }
}
