package com.gernalix.personalhub.core.database

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

data class DatabaseProfile(val id: String, val name: String)

enum class DatabaseProfileInitMode {
    EMPTY,
    CLONE_CURRENT,
}

/**
 * Device-local registry for complete PersonalHub database profiles.
 *
 * Exactly one profile is active at a time and is mounted as the canonical personalhub.db.
 * Inactive profiles are verified detached snapshots; switching swaps the whole database so
 * every feature changes universe atomically.
 */
object DatabaseProfiles {
    const val DEFAULT_PROFILE_ID = "personal"
    private const val PREFS = "personalhub_profiles"
    private const val KEY_ACTIVE = "active_profile"
    private const val KEY_PROFILES = "profiles_json"
    private const val PROFILE_DIR = "database-profiles"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    private fun ensureInitialized(context: Context) {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_PROFILES)) {
            val array = JSONArray().put(
                JSONObject()
                    .put("id", DEFAULT_PROFILE_ID)
                    .put("name", "Personal"),
            )
            check(
                prefs.edit()
                    .putString(KEY_PROFILES, array.toString())
                    .putString(KEY_ACTIVE, DEFAULT_PROFILE_ID)
                    .commit(),
            )
        }
    }

    @Synchronized
    fun list(context: Context): List<DatabaseProfile> {
        ensureInitialized(context)
        val raw = prefs(context).getString(KEY_PROFILES, "[]").orEmpty()
        val array = JSONArray(raw)
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.getJSONObject(index)
                add(DatabaseProfile(value.getString("id"), value.getString("name")))
            }
        }
    }

    @Synchronized
    fun activeProfileId(context: Context): String {
        ensureInitialized(context)
        val known = list(context).mapTo(linkedSetOf()) { it.id }
        val configured = prefs(context).getString(KEY_ACTIVE, DEFAULT_PROFILE_ID)
        return configured?.takeIf(known::contains) ?: DEFAULT_PROFILE_ID
    }

    fun active(context: Context): DatabaseProfile =
        list(context).first { it.id == activeProfileId(context) }

    internal fun preferenceSuffix(context: Context): String =
        activeProfileId(context).let { if (it == DEFAULT_PROFILE_ID) "" else "_$it" }

    internal fun exportStem(context: Context): String =
        activeProfileId(context).let {
            if (it == DEFAULT_PROFILE_ID) "personalhub" else "personalhub-${it.take(8)}"
        }

    internal fun databaseFile(context: Context, profileId: String): File =
        File(File(context.filesDir, PROFILE_DIR), "$profileId/${PersonalHubDatabase.DB_NAME}")

    @Synchronized
    fun create(
        context: Context,
        requestedName: String,
        initMode: DatabaseProfileInitMode,
    ): DatabaseProfile {
        val name = requestedName.trim().replace(Regex("\\s+"), " ")
        require(name.isNotBlank()) { "Profile name is required" }
        require(
            list(context).none { it.name.equals(name, ignoreCase = true) },
        ) { "A profile with this name already exists" }

        val id = UUID.randomUUID().toString().lowercase(Locale.US)
        val file = databaseFile(context, id)
        try {
            when (initMode) {
                DatabaseProfileInitMode.EMPTY ->
                    DatabaseVault.createEmptyProfileDatabase(context, file)
                DatabaseProfileInitMode.CLONE_CURRENT ->
                    DatabaseVault.snapshotProfileCopy(context, file)
            }
            val updated = list(context) + DatabaseProfile(id, name)
            persistProfiles(context, updated)
            return updated.last()
        } catch (error: Throwable) {
            file.parentFile?.deleteRecursively()
            throw error
        }
    }

    @Synchronized
    fun rename(context: Context, profileId: String, requestedName: String) {
        val name = requestedName.trim().replace(Regex("\\s+"), " ")
        require(name.isNotBlank()) { "Profile name is required" }
        val current = list(context)
        require(current.any { it.id == profileId }) { "Unknown profile" }
        require(
            current.none { it.id != profileId && it.name.equals(name, ignoreCase = true) },
        ) { "A profile with this name already exists" }
        persistProfiles(
            context,
            current.map { if (it.id == profileId) it.copy(name = name) else it },
        )
    }

    @Synchronized
    fun delete(context: Context, profileId: String) {
        require(profileId != activeProfileId(context)) { "The active profile cannot be deleted" }
        val current = list(context)
        require(current.size > 1) { "At least one profile is required" }
        require(current.any { it.id == profileId }) { "Unknown profile" }
        databaseFile(context, profileId).parentFile?.deleteRecursively()
        persistProfiles(context, current.filterNot { it.id == profileId })
    }

    /**
     * Returns true when a process restart is required.
     *
     * The database gate intentionally stays frozen after a successful swap so stale ViewModels
     * cannot write the previous profile back into the newly mounted database.
     */
    @Synchronized
    fun switch(context: Context, targetProfileId: String): Boolean {
        val currentId = activeProfileId(context)
        if (currentId == targetProfileId) return false
        val target = list(context).firstOrNull { it.id == targetProfileId }
            ?: error("Unknown profile")
        val targetFile = databaseFile(context, target.id)
        require(targetFile.isFile) { "Profile database is missing" }
        DatabaseVault.switchProfileDatabase(
            context = context,
            currentProfileFile = databaseFile(context, currentId),
            targetProfileFile = targetFile,
        )
        check(prefs(context).edit().putString(KEY_ACTIVE, target.id).commit())
        return true
    }

    @Synchronized
    private fun persistProfiles(context: Context, profiles: List<DatabaseProfile>) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(JSONObject().put("id", profile.id).put("name", profile.name))
        }
        check(prefs(context).edit().putString(KEY_PROFILES, array.toString()).commit())
    }
}
