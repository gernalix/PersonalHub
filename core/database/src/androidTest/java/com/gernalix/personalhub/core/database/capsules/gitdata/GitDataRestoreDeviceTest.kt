package com.gernalix.personalhub.core.database.capsules.gitdata

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gernalix.personalhub.core.database.PersonalHubDatabase
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
        PersonalHubDatabase.closeInstance()
        context.deleteDatabase(PersonalHubDatabase.DB_NAME)
    }
    @After
    fun cleanup() {
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
        LocalRawHttpServer(
            mapOf(
                "/repos/owner/data/contents/state/manifest.json" to manifest.toString().toByteArray(),
                "/repos/owner/data/contents/state/tables/finance_accounts.jsonl" to rowBytes,
            ),
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
            assertEquals(7L, rawScalar(restored, "SELECT generation FROM hub_generation WHERE id=1"))
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
