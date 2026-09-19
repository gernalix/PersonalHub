package com.gernalix.personalhub

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class TableProbe(vararg val tables: String)

/** A new runtime table fails this gate until it has one named production-path probe. */
@RunWith(AndroidJUnit4::class)
class PersistenceSchemaCoverageDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun everyAppOwnedRuntimeTableHasExactlyOneExecutableProbe() {
        check(context.packageName == "com.gernalix.personalhub.qa")
        val lines = InstrumentationRegistry.getInstrumentation().context.assets
            .open("persistence_table_registry.tsv").bufferedReader().use { it.readLines() }
        val entries = lines.filter { it.isNotBlank() && !it.startsWith('#') }.map { line ->
            val parts = line.split('\t')
            require(parts.size == 2 && parts.all(String::isNotBlank)) { "Invalid registry row: $line" }
            parts[0] to parts[1]
        }
        val registry = entries.toMap()
        assertEquals("Duplicate table in persistence registry", entries.size, registry.size)

        val db = PersonalHubDatabase.get(context).openHelper.readableDatabase
        val runtimeTables = db.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        // These three are created by Android/SQLite/Room and hold no PersonalHub entries.
        val systemTables = setOf("android_metadata", "room_master_table", "sqlite_sequence")
        assertEquals("Unexpected system table inventory", systemTables, runtimeTables.intersect(systemTables))
        val appTables = runtimeTables - systemTables
        assertEquals("Registry/schema mismatch", appTables, registry.keys)

        val missing = registry.filterValues { it == "UNTESTED" }.keys.sorted()
        for ((table, probe) in registry) {
            if (probe == "UNTESTED") continue
            val (className, methodName) = probe.split('#').also { require(it.size == 2) }
            val clazz = Class.forName("com.gernalix.personalhub.$className")
            val method = clazz.getDeclaredMethod(methodName)
            assertTrue("$table probe is not a JUnit test", method.isAnnotationPresent(Test::class.java))
            assertTrue("$table not declared by $probe", table in method.getAnnotation(TableProbe::class.java).tables)
        }
        assertTrue("TABLE_COVERAGE=${registry.size - missing.size}/${registry.size}; UNTESTED=$missing", missing.isEmpty())
    }
}
