package com.gernalix.personalhub.core.database

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

@Entity(tableName = "hub_preferences")
data class HubPreferences(@PrimaryKey val namespace: String, val json: String)

/** User settings are database rows. SAF grants and transfer diagnostics remain device-local. */
class DatabasePreferences(context: Context, private val namespace: String) : SharedPreferences {
    private val context = context.applicationContext
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val timer get() = namespace == "ui_prefs"
    private fun read(): JSONObject {
        val db = PersonalHubDatabase.get(context).openHelper.readableDatabase
        val sql = if (timer) "SELECT json FROM ui_prefs_mirror WHERE id=1" else "SELECT json FROM hub_preferences WHERE namespace=?"
        return db.query(sql, if (timer) emptyArray() else arrayOf(namespace)).use {
            if (it.moveToFirst()) JSONObject(it.getString(0)) else JSONObject()
        }
    }
    override fun getAll(): MutableMap<String, *> = read().let { json ->
        json.keys().asSequence().associateWith { key ->
            when (val value = json.get(key)) {
                is JSONArray -> (0 until value.length()).map { value.getString(it) }.toSet()
                JSONObject.NULL -> null
                else -> value
            }
        }.toMutableMap()
    }
    override fun contains(key: String?) = read().has(key)
    override fun getString(key: String?, defValue: String?): String? = read().let { if (key != null && it.has(key)) it.getString(key) else defValue }
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = read().let { json ->
        if (key == null || !json.has(key)) defValues?.toMutableSet() else json.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) }.toMutableSet() }
    }
    override fun getInt(key: String?, defValue: Int) = read().optInt(key, defValue)
    override fun getLong(key: String?, defValue: Long) = read().optLong(key, defValue)
    override fun getFloat(key: String?, defValue: Float) = read().optDouble(key, defValue.toDouble()).toFloat()
    override fun getBoolean(key: String?, defValue: Boolean) = read().optBoolean(key, defValue)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) { listener?.let(listeners::add) }
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) { listeners.remove(listener) }
    override fun edit(): SharedPreferences.Editor = Editor()
    private inner class Editor : SharedPreferences.Editor {
        private val changes = linkedMapOf<String, Any?>()
        private var clear = false
        private fun put(key: String?, value: Any?): SharedPreferences.Editor = apply { changes[requireNotNull(key)] = value }
        override fun putString(key: String?, value: String?) = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.let { JSONArray(it.sorted()) })
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)
        override fun remove(key: String?) = put(key, null)
        override fun clear(): SharedPreferences.Editor = apply { clear = true }
        override fun apply() { check(commit()) }
        override fun commit(): Boolean {
            val changed = mutableSetOf<String>()
            DatabaseGate.access {
                val db = PersonalHubDatabase.get(context).openHelper.writableDatabase
                db.beginTransaction()
                try {
                    val before = read()
                    val after = if (clear) JSONObject() else JSONObject(before.toString())
                    if (clear) changed.addAll(before.keys().asSequence().toList())
                    changes.forEach { (key, value) ->
                        if (before.opt(key)?.toString() != value?.toString()) changed.add(key)
                        if (value == null) after.remove(key) else after.put(key, value)
                    }
                    if (changed.isNotEmpty()) {
                        if (timer) db.execSQL("INSERT OR REPLACE INTO ui_prefs_mirror(id,json,saved_at_ms) VALUES(1,?,?)", arrayOf<Any?>(after.toString(), System.currentTimeMillis()))
                        else db.execSQL("INSERT OR REPLACE INTO hub_preferences(namespace,json) VALUES(?,?)", arrayOf(namespace, after.toString()))
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            changed.forEach { key -> listeners.forEach { it.onSharedPreferenceChanged(this@DatabasePreferences, key) } }
            return true
        }
    }
}
