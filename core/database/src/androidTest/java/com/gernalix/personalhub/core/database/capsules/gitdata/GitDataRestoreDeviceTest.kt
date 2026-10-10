package com.gernalix.personalhub.core.database.capsules.gitdata

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.database.capsules.identity.CanonicalIdentityCapsule
import com.gernalix.personalhub.contracts.database.HubExternalIdentity
import com.gernalix.personalhub.contracts.database.SinceWhenCounterEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class GitDataRestoreDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() {
        check(context.packageName == "com.gernalix.personalhub.core.database.test")
        PersonalHubDatabase.closeInstance()
        context.deleteDatabase(PersonalHubDatabase.DB_NAME)
    }
    @After
    fun cleanup() {
        check(context.packageName == "com.gernalix.personalhub.core.database.test")
        PersonalHubDatabase.closeInstance()
        context.deleteDatabase(PersonalHubDatabase.DB_NAME)
    }

    @Test
    fun restoreUsesValidatedStagingBeforeReplacingLiveDatabase() {
        val db = PersonalHubDatabase.get(context).openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO finance_accounts(id,name,currency,openingBalance,openedAt,included) " +
                "VALUES('live','Live','DKK','0',1,1)",
        )
        db.execSQL("DELETE FROM finance_accounts WHERE id='live'")
        db.execSQL("INSERT INTO finance_accounts(id,name,currency,openingBalance,openedAt,included) VALUES('restored','Restored','DKK','0',2,1)")
        GitDataTracking.install(db,enqueueAll=true)
        db.execSQL("INSERT INTO contacts(id,public_id,created_at,updated_at) VALUES(1,'person-losing',1,1),(2,'person-surviving',1,1)")
        db.execSQL("INSERT INTO hub_entity_aliases VALUES('person-losing','person-surviving','explicit fixture merge',1)")
        CanonicalIdentityCapsule(context).linkExternal(HubExternalIdentity(canonicalId="person-surviving",entityKind="people/person",system="fixture",sourceScope="account",externalId="native",linkMethod="explicit",createdAt=1,updatedAt=1))
        db.execSQL("INSERT INTO finance_transactions(id,uuid,accountId,amount,currency,fromReceipt,notes,occurredAt,createdAt,updatedAt,personId) VALUES(7,'tx-restored','restored','3','DKK',0,'Fixture',1,1,1,2)")
        val counterId=runBlocking { PersonalHubDatabase.get(context).sinceWhenCounterDao().insert(SinceWhenCounterEntity(title="Cross-module source",initialTimestamp=1,createdAt=1,sourceEntityType="soldi/transaction",sourceEntityId="tx-restored")) }
        val counterCanonical=CanonicalIdentityCapsule(context).canonicalId("since_when_counters",counterId)
        val bundle=requireNotNull(GitDataFormat.exportPending(context))
        assertEquals("personalhub.canonical.v1",bundle.manifest.getString("identity_contract"))
        assertTrue(bundle.events.any { it.entityKind == "soldi/transaction" && it.canonicalId == "tx-restored" })
        val expectedGeneration=bundle.generation
        db.execSQL("UPDATE finance_accounts SET name='Local change' WHERE id='restored'")
        db.execSQL("INSERT INTO finance_accounts(id,name,currency,openingBalance,openedAt,included) VALUES('live','Live','DKK','0',1,1)")
        LocalRawHttpServer(
            bundle.files.mapKeys { (path, _) -> "/repos/owner/data/contents/$path" },
        ).use { server ->
            GitStateRestorer.restore(
                context,
                GitHubDataTransport(
                    GitRepository("owner", "data"),
                    "token",
                    "http://127.0.0.1:${server.port}/repos",
                ),
                "abcdef1",
                null,
                "main",
            )
        }
        android.database.sqlite.SQLiteDatabase.openDatabase(
            context.getDatabasePath(PersonalHubDatabase.DB_NAME).path,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        ).use { restored ->
            assertEquals(0L, rawScalar(restored, "SELECT COUNT(*) FROM finance_accounts WHERE id='live'"))
            assertEquals("Restored", rawText(restored, "SELECT name FROM finance_accounts WHERE id='restored'"))
            assertEquals(expectedGeneration, rawScalar(restored, "SELECT generation FROM hub_generation WHERE id=1"))
            assertEquals("restored",rawText(restored,"SELECT canonical_id FROM hub_entities WHERE local_table='finance_accounts' AND local_key='restored'"))
            assertEquals("person-surviving",rawText(restored,"SELECT person_canonical_id FROM finance_transactions WHERE uuid='tx-restored'"))
            assertEquals("tx-restored",rawText(restored,"SELECT source_entity_id FROM since_when_counters WHERE id=$counterId"))
            assertEquals(counterCanonical,rawText(restored,"SELECT canonical_id FROM since_when_counters WHERE id=$counterId"))
            assertEquals("person-surviving",rawText(restored,"SELECT canonical_id FROM hub_entity_aliases WHERE alias_canonical_id='person-losing'"))
            assertEquals("person-surviving",rawText(restored,"SELECT canonical_id FROM hub_external_identities WHERE system='fixture' AND external_id='native'"))
            assertEquals(0L, rawScalar(restored, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
            assertEquals("ok", rawText(restored, "PRAGMA quick_check"))
        }
    }

    private fun rawScalar(db: android.database.sqlite.SQLiteDatabase, sql: String): Long =
        db.rawQuery(sql, null).use {
            assertTrue(it.moveToFirst())
            it.getLong(0)
        }

    private fun rawText(db: android.database.sqlite.SQLiteDatabase, sql: String): String =
        db.rawQuery(sql, null).use {
            assertTrue(it.moveToFirst())
            it.getString(0)
        }
}

private class LocalRawHttpServer(
    private val files: Map<String, ByteArray>,
) : AutoCloseable {
    private val socket = ServerSocket(0)
    private val executor = Executors.newSingleThreadExecutor()
    val port: Int get() = socket.localPort

    init {
        executor.submit {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.use {
                    val reader = BufferedReader(InputStreamReader(it.getInputStream()))
                    val request = reader.readLine().orEmpty()
                    while (reader.readLine()?.isNotEmpty() == true) Unit
                    val path = request.split(" ").getOrNull(1)?.substringBefore("?").orEmpty()
                    val body = files[path]
                    val out = it.getOutputStream()
                    if (body == null) {
                        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    } else {
                        out.write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(),
                        )
                        out.write(body)
                    }
                    out.flush()
                }
            }
        }
    }

    override fun close() {
        runCatching { socket.close() }
        executor.shutdownNow()
    }
}
