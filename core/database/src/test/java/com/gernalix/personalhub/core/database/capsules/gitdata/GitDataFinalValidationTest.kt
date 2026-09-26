package com.gernalix.personalhub.core.database.capsules.gitdata

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.database.capsules.sync.SyncJournal
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class GitDataFinalValidationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @Before
    fun reset() {
        PersonalHubDatabase.closeInstance()
        context.deleteDatabase(PersonalHubDatabase.DB_NAME)
        GitDataSettings.stateManifestFile(context).delete()
        context.getSharedPreferences("personalhub_git_data_status", Context.MODE_PRIVATE).edit().clear().commit()
        java.io.File(context.noBackupFilesDir, "git-data.enc").delete()
    }

    @After
    fun cleanup() {
        PersonalHubDatabase.closeInstance()
        context.deleteDatabase(PersonalHubDatabase.DB_NAME)
        GitDataSettings.stateManifestFile(context).delete()
        context.getSharedPreferences("personalhub_git_data_status", Context.MODE_PRIVATE).edit().clear().commit()
        java.io.File(context.noBackupFilesDir, "git-data.enc").delete()
    }

    @Test
    fun displayProjectionRetainsOnlyBoundedHumanValues() {
        val row = JSONObject().put("nickname", "Casa").put("notes", "before")
            .put("uuid", "technical-id").put("updated_at", 123L)
        val projected = JSONObject(requireNotNull(GitHistoryStore.projectJson(row, "notes,uuid,updated_at")))
        assertEquals("Casa", projected.getString("nickname"))
        assertEquals("before", projected.getString("notes"))
        assertFalse(projected.has("uuid"))
        assertFalse(projected.has("updated_at"))
    }

    private fun event(id: String, operation: String = "UPDATE", at: Long = 1L) = GitEditEvent(
        id = id,
        occurredAt = at,
        author = "user",
        source = "ui",
        reason = null,
        groupId = null,
        table = "finance_accounts",
        operation = operation,
        rowKey = "id",
        columns = "name",
        beforePayload = null,
        afterPayload = null,
    )

    private fun bundle(events: List<GitEditEvent>, currentRows: Long) = GitExportBundle(
        files = emptyMap(),
        manifest = JSONObject().put(
            "tables",
            JSONArray().put(JSONObject().put("name", "finance_accounts").put("rows", currentRows)),
        ),
        pending = emptyList(),
        events = events,
        eventMeta = emptyList(),
        generation = 1L,
    )

    @Test
    fun destructiveGuardMatchesDocumentedThresholds() {
        val safe = GitDataSafety.evaluate(bundle(List(99) { event("e$it", "DELETE") }, 401))
        assertFalse(safe.suspicious)

        val destructive = GitDataSafety.evaluate(bundle(List(100) { event("d$it", "DELETE") }, 400))
        assertTrue(destructive.suspicious)
        assertEquals(100, destructive.deleteCount)

        val huge = GitDataSafety.evaluate(bundle(List(10_000) { event("h$it") }, 1))
        assertTrue(huge.suspicious)
        assertTrue(huge.reason!!.contains("history events"))
    }

    @Test
    fun pendingStateSurvivesUntilAcknowledgedAndLargeTableUsesEightShards() {
        val db = PersonalHubDatabase.get(context).openHelper.writableDatabase
        db.beginTransaction()
        try {
            repeat(501) { index ->
                db.execSQL(
                    "INSERT INTO finance_accounts(id,name,currency,openingBalance,openedAt,included) VALUES(?,?,?,?,?,?)",
                    arrayOf<Any?>("acct-$index", "Account $index", "DKK", "0", index.toLong(), 1),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        GitDataTracking.install(db, enqueueAll = false)
        db.execSQL("DELETE FROM " + GitDataTracking.EVENTS_TABLE)
        db.execSQL("DELETE FROM " + GitDataTracking.TABLE)
        db.execSQL(
            "INSERT INTO " + GitDataTracking.TABLE + "(table_name,revision) VALUES('finance_accounts',1)",
        )

        val bundle = requireNotNull(GitDataFormat.exportPending(context))
        val tables = bundle.manifest.getJSONArray("tables")
        val finance = (0 until tables.length())
            .map { tables.getJSONObject(it) }
            .single { it.getString("name") == "finance_accounts" }
        assertEquals(8, finance.getInt("shard_count"))
        val shards = finance.getJSONArray("shards")
        assertEquals(8, shards.length())
        for (i in 0 until shards.length()) {
            val shard = shards.getJSONObject(i)
            val bytes = requireNotNull(bundle.files[shard.getString("path")])
            assertEquals(shard.getString("sha256"), GitDataFormat.sha256(bytes))
        }
        assertEquals(1, GitDataTracking.pending(db).size)

        GitDataFormat.acknowledge(context, bundle, "revision-1")
        assertTrue(GitDataTracking.pending(db).isEmpty())
    }

    @Test
    fun signedHistoryDetectsPayloadTampering() {
        val payload = "immutable-history".toByteArray()
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(payload)
            sign()
        }
        val document = JSONObject()
            .put("format_version", 1)
            .put("algorithm", "SHA256withECDSA")
            .put("key_alias", "test")
            .put("payload_sha256", GitDataFormat.sha256(payload))
            .put("public_key_spki_base64", Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP))
            .put("signature_base64", Base64.encodeToString(signature, Base64.NO_WRAP))
            .toString().toByteArray()
        assertTrue(GitDataSigner.verify(payload, document))
        assertFalse(GitDataSigner.verify("tampered".toByteArray(), document))
    }

    @Test
    fun technicalTablesNeverEmitSemanticHistoryEvents() {
        val technical = listOf(
            "snapshot",
            "snapshot_history",
            "snapshot_payloads",
            "audit_events",
            "integrity_stats",
            "ui_prefs_mirror",
            "backup_metadata",
            "global_stats_state",
            "route_distance_cache",
            "notification_state",
            "history_audit_log",
            "history_actions",
            "hub_activity_log",
        )
        technical.forEach { table ->
            val sql = GitDataTracking.trigger(table, listOf("id"), listOf("id"), "INSERT")
            assertTrue(table, sql.contains("hub_git_dirty_" + table + "_INSERT"))
            assertFalse(table, sql.contains(GitDataTracking.EVENTS_TABLE))
        }
        assertTrue(
            GitDataTracking.trigger("finance_accounts", listOf("id", "name"), listOf("id"), "UPDATE")
                .contains(GitDataTracking.EVENTS_TABLE),
        )
        assertTrue(GitHistoryStore.TABLE in SyncJournal.excluded)
        assertTrue(GitHistoryStore.FIELD_STATS_TABLE in SyncJournal.excluded)
    }
    @Test
    fun patchPreviewIsIsolatedAndApplyIsAtomicAndFailClosed() {
        val db = PersonalHubDatabase.get(context).openHelper.writableDatabase
        GitDataTracking.install(db, enqueueAll = false)
        db.execSQL(
            "INSERT INTO finance_accounts(id,name,currency,openingBalance,openedAt,included) " +
                "VALUES('acct','Before','DKK','0',1,1)",
        )

        val patch = JSONObject()
            .put("format_version", 1)
            .put("schema_version", PersonalHubDatabase.SCHEMA_VERSION)
            .put("patch_id", "patch-1")
            .put("author", "chatgpt")
            .put(
                "operations",
                JSONArray().put(
                    JSONObject()
                        .put("op", "update")
                        .put("table", "finance_accounts")
                        .put("key", JSONObject().put("id", "acct"))
                        .put("expect", JSONObject().put("name", "Before"))
                        .put("values", JSONObject().put("name", "After")),
                ),
            )
            .toString()
            .toByteArray()
        val preview = GitPatchEngine.preview(context, patch)
        assertEquals(1, preview.updates)
        assertEquals(listOf("finance_accounts"), preview.tables)
        assertEquals("Before", scalar("SELECT name FROM finance_accounts WHERE id='acct'"))

        GitPatchEngine.apply(context, patch)
        assertEquals("After", scalar("SELECT name FROM finance_accounts WHERE id='acct'"))
        val tracked = GitDataTracking.events(db).last { it.table == "finance_accounts" }
        assertEquals("chatgpt", tracked.author)
        assertEquals("remote_patch", tracked.source)
        assertEquals("patch-1", tracked.groupId)

        assertThrows(IllegalArgumentException::class.java) { GitPatchEngine.apply(context, patch) }
        assertEquals("After", scalar("SELECT name FROM finance_accounts WHERE id='acct'"))

        val forbidden = JSONObject(String(patch))
            .put("patch_id", "forbidden")
            .put(
                "operations",
                JSONArray().put(
                    JSONObject()
                        .put("op", "insert")
                        .put("table", "hub_sync_pending")
                        .put("key", JSONObject().put("table_name", "x").put("row_key", "y"))
                        .put("values", JSONObject().put("revision", 1)),
                ),
            ).toString().toByteArray()
        assertThrows(IllegalArgumentException::class.java) { GitPatchEngine.apply(context, forbidden) }
        val badFk = JSONObject(String(patch))
            .put("patch_id", "bad-fk")
            .put(
                "operations",
                JSONArray().put(
                    JSONObject()
                        .put("op", "insert")
                        .put("table", "place_events")
                        .put("key", JSONObject().put("id", 99))
                        .put(
                            "values",
                            JSONObject()
                                .put("event_uuid", "event-99")
                                .put("session_uuid", "session-99")
                                .put("place_id", "missing-place")
                                .put("event_type", "CHECK_IN")
                                .put("timestamp", 99)
                                .put("source", "test"),
                        ),
                ),
            ).toString().toByteArray()
        assertThrows(Exception::class.java) { GitPatchEngine.apply(context, badFk) }
        assertEquals(0L, count("SELECT COUNT(*) FROM place_events WHERE id=99"))
        assertEquals(0L, count("SELECT COUNT(*) FROM pragma_foreign_key_check"))
    }

    @Test
    fun historyIndexIsIdempotentFilterableAndRebuildable() {
        val db = PersonalHubDatabase.get(context).openHelper.writableDatabase
        GitHistoryStore.install(db)
        val first = event("first", at = 1_000)
        val second = event("second", at = 3_000)
        val meta = listOf(
            GitHistoryCommitMeta("first", "history/a.jsonl", "name"),
            GitHistoryCommitMeta("second", "history/b.jsonl", "name"),
        )
        GitHistoryStore.indexCommitted(db, listOf(first, second), meta, "sha")
        GitHistoryStore.indexCommitted(db, listOf(first, second), meta, "sha")

        assertEquals(listOf("second", "first"), GitHistoryStore.recent(db).map { it.id })
        assertEquals(2, GitHistoryStore.recent(db, author = "user").size)
        assertEquals(2L, count("SELECT COUNT(*) FROM " + GitHistoryStore.TABLE))
        assertEquals(2_000L, GitHistoryStore.averageFieldValueLifetimeMs(db))

        GitHistoryStore.markReverted(db, "second", "revert-group")
        assertEquals("revert-group", GitHistoryStore.find(db, "second")!!.revertedBy)

        db.execSQL("DELETE FROM " + GitHistoryStore.FIELD_STATS_TABLE)
        GitHistoryStore.rebuildFieldStats(db)
        assertEquals(2_000L, GitHistoryStore.averageFieldValueLifetimeMs(db))
    }

    @Test
    fun stateRestoreValidatesHashBeforeReplacingLiveDatabase() {
        val db = PersonalHubDatabase.get(context).openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO finance_accounts(id,name,currency,openingBalance,openedAt,included) " +
                "VALUES('live','Live','DKK','0',1,1)",
        )
        val row = JSONObject()
            .put("id", "restored")
            .put("name", "Restored")
            .put("currency", "DKK")
            .put("openingBalance", "0")
            .put("openedAt", 2)
            .put("included", 1)
            .toString() + "\n"
        val manifest = JSONObject()
            .put("format_version", 2)
            .put("schema_version", PersonalHubDatabase.SCHEMA_VERSION)
            .put("generation", 7)
            .put(
                "tables",
                JSONArray().put(
                    JSONObject()
                        .put("name", "finance_accounts")
                        .put("path", "state/tables/finance_accounts.jsonl")
                        .put("sha256", "wrong-hash"),
                ),
            )
        withFiles(
            mapOf(
                "state/manifest.json" to manifest.toString().toByteArray(),
                "state/tables/finance_accounts.jsonl" to row.toByteArray(),
            ),
        ) { transport ->
            assertThrows(IllegalArgumentException::class.java) {
                GitStateRestorer.restore(
                    context = context,
                    transport = transport,
                    revision = "abcdef1",
                    control = null,
                    controlRef = "main",
                )
            }
        }
        assertEquals("Live", scalar("SELECT name FROM finance_accounts WHERE id='live'"))
        assertEquals(0L, count("SELECT COUNT(*) FROM finance_accounts WHERE id='restored'"))
    }

    @Ignore("Requires AndroidKeyStore; covered by emulator DatabaseVault import gate")
    @Test
    fun stateRestoreReplacesLiveOnlyAfterValidatedStaging() {
        val db = PersonalHubDatabase.get(context).openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO finance_accounts(id,name,currency,openingBalance,openedAt,included) " +
                "VALUES('live','Live','DKK','0',1,1)",
        )
        val rowBytes = (
            JSONObject()
                .put("id", "restored")
                .put("name", "Restored")
                .put("currency", "DKK")
                .put("openingBalance", "0")
                .put("openedAt", 2)
                .put("included", 1)
                .toString() + "\n"
        ).toByteArray()
        val manifest = JSONObject()
            .put("format_version", 2)
            .put("schema_version", PersonalHubDatabase.SCHEMA_VERSION)
            .put("generation", 7)
            .put(
                "tables",
                JSONArray().put(
                    JSONObject()
                        .put("name", "finance_accounts")
                        .put("path", "state/tables/finance_accounts.jsonl")
                        .put("sha256", GitDataFormat.sha256(rowBytes)),
                ),
            )
        withFiles(
            mapOf(
                "state/manifest.json" to manifest.toString().toByteArray(),
                "state/tables/finance_accounts.jsonl" to rowBytes,
            ),
        ) { transport ->
            GitStateRestorer.restore(
                context = context,
                transport = transport,
                revision = "abcdef1",
                control = null,
                controlRef = "main",
            )
        }
        assertEquals(0L, count("SELECT COUNT(*) FROM finance_accounts WHERE id='live'"))
        assertEquals("Restored", scalar("SELECT name FROM finance_accounts WHERE id='restored'"))
        assertEquals(7L, count("SELECT generation FROM hub_generation WHERE id=1"))
        assertEquals(0L, count("SELECT COUNT(*) FROM pragma_foreign_key_check"))
    }

    private fun scalar(sql: String): String =
        PersonalHubDatabase.get(context).openHelper.writableDatabase.query(sql).use {
            assertTrue(it.moveToFirst())
            it.getString(0)
        }

    private fun count(sql: String): Long =
        PersonalHubDatabase.get(context).openHelper.writableDatabase.query(sql).use {
            assertTrue(it.moveToFirst())
            it.getLong(0)
        }

    private fun withFiles(
        files: Map<String, ByteArray>,
        block: (GitHubDataTransport) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val prefix = "/repos/owner/data/contents/"
            val key = exchange.requestURI.path.removePrefix(prefix)
            val bytes = files[key]
            if (bytes == null) {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
            } else {
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        try {
            block(
                GitHubDataTransport(
                    GitRepository("owner", "data"),
                    "token",
                    "http://127.0.0.1:" + server.address.port + "/repos",
                ),
            )
        } finally {
            server.stop(0)
        }
    }
}
