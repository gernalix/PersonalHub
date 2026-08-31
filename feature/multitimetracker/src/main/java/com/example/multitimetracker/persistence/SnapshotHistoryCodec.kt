package com.example.multitimetracker.persistence

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal object SnapshotHistoryCodec {
    const val CHECKPOINT_INTERVAL_MS: Long = 30L * 60L * 1000L
    const val MAX_DELTA_BYTES: Int = 256 * 1024

    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun diffOrNull(baseJson: String, nextJson: String): String? {
        if (baseJson == nextJson) return null
        val delta = diffObject(JSONObject(baseJson), JSONObject(nextJson))
        return if (delta.length() == 0) null else delta.toString()
    }

    fun applyDelta(baseJson: String, deltaJson: String): String {
        val base = JSONObject(baseJson)
        val delta = JSONObject(deltaJson)
        return applyObjectDelta(base, delta).toString()
    }

    fun shouldStoreCheckpoint(lastCheckpointMs: Long?, nowMs: Long, deltaJson: String?): Boolean {
        if (lastCheckpointMs == null) return true
        if (nowMs - lastCheckpointMs >= CHECKPOINT_INTERVAL_MS) return true
        if (deltaJson == null) return false
        return deltaJson.toByteArray(Charsets.UTF_8).size > MAX_DELTA_BYTES
    }

    private fun diffObject(base: JSONObject, next: JSONObject): JSONObject {
        val set = JSONObject()
        val remove = JSONArray()
        val keys = linkedSetOf<String>()
        base.keys().forEach { keys.add(it) }
        next.keys().forEach { keys.add(it) }

        keys.forEach { key ->
            val baseHas = base.has(key)
            val nextHas = next.has(key)
            when {
                baseHas && !nextHas -> remove.put(key)
                !baseHas && nextHas -> set.put(key, next.get(key))
                else -> {
                    val before = base.get(key)
                    val after = next.get(key)
                    val patch = diffValue(key, before, after)
                    if (patch != null) set.put(key, patch)
                }
            }
        }

        return JSONObject().apply {
            if (set.length() > 0) put("set", set)
            if (remove.length() > 0) put("remove", remove)
        }
    }

    private fun diffValue(key: String, before: Any?, after: Any?): Any? {
        if (jsonEqual(before, after)) return null
        if (before is JSONObject && after is JSONObject) {
            val child = diffObject(before, after)
            return if (child.length() == 0) null else JSONObject().put("\$objectDelta", child)
        }
        if (before is JSONArray && after is JSONArray) {
            val arrayDelta = diffArrayByStableKey(key, before, after)
            if (arrayDelta != null) return arrayDelta
        }
        return after ?: JSONObject.NULL
    }

    private fun applyObjectDelta(base: JSONObject, delta: JSONObject): JSONObject {
        val out = JSONObject(base.toString())
        delta.optJSONArray("remove")?.let { remove ->
            for (i in 0 until remove.length()) {
                out.remove(remove.getString(i))
            }
        }
        delta.optJSONObject("set")?.let { set ->
            set.keys().forEach { key ->
                val value = set.get(key)
                val applied = when {
                    value is JSONObject && value.has("\$objectDelta") -> {
                        val current = out.optJSONObject(key) ?: JSONObject()
                        applyObjectDelta(current, value.getJSONObject("\$objectDelta"))
                    }
                    value is JSONObject && value.optBoolean("\$arrayDeltaByKey", false) -> {
                        applyArrayDelta(out.optJSONArray(key) ?: JSONArray(), value)
                    }
                    else -> value
                }
                out.put(key, applied)
            }
        }
        return out
    }

    private fun diffArrayByStableKey(name: String, before: JSONArray, after: JSONArray): JSONObject? {
        val keyFields = stableKeyFields(name) ?: return null
        val beforeMap = keyedObjects(before, keyFields) ?: return null
        val afterMap = keyedObjects(after, keyFields) ?: return null

        val removed = JSONArray()
        beforeMap.keys.filterNot { afterMap.containsKey(it) }.forEach { removed.put(it) }

        val set = JSONArray()
        afterMap.forEach { (key, afterObj) ->
            val beforeObj = beforeMap[key]
            if (beforeObj == null || !jsonEqual(beforeObj, afterObj)) {
                set.put(JSONObject().put("\$key", key).put("value", afterObj))
            }
        }

        if (removed.length() == 0 && set.length() == 0) return null

        return JSONObject()
            .put("\$arrayDeltaByKey", true)
            .put("keyFields", JSONArray().apply { keyFields.forEach { put(it) } })
            .put("remove", removed)
            .put("set", set)
    }

    private fun applyArrayDelta(base: JSONArray, delta: JSONObject): JSONArray {
        val keyFields = mutableListOf<String>()
        val fields = delta.getJSONArray("keyFields")
        for (i in 0 until fields.length()) keyFields.add(fields.getString(i))

        val map = linkedMapOf<String, JSONObject>()
        for (i in 0 until base.length()) {
            val obj = base.optJSONObject(i) ?: continue
            val key = stableKey(obj, keyFields) ?: continue
            map[key] = obj
        }

        delta.optJSONArray("remove")?.let { removed ->
            for (i in 0 until removed.length()) map.remove(removed.getString(i))
        }
        delta.optJSONArray("set")?.let { set ->
            for (i in 0 until set.length()) {
                val row = set.getJSONObject(i)
                map[row.getString("\$key")] = row.getJSONObject("value")
            }
        }

        return JSONArray().apply {
            map.values.forEach { put(it) }
        }
    }

    private fun stableKeyFields(name: String): List<String>? = when (name) {
        "tasks", "tags", "lifePeriods", "timeFenceRules", "chains", "chronologySessions", "runningSessions" -> listOf("id")
        "closedSessions" -> listOf("sessionId")
        "tagSessions" -> listOf("tagId", "sessionId", "startTs", "endTs")
        "activeSessionStart" -> listOf("sessionId")
        "activeTagStart" -> listOf("sessionId", "tagId")
        "tagParents" -> listOf("childId", "parentId")
        else -> null
    }

    private fun keyedObjects(array: JSONArray, keyFields: List<String>): LinkedHashMap<String, JSONObject>? {
        val out = linkedMapOf<String, JSONObject>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: return null
            val key = stableKey(obj, keyFields) ?: return null
            if (out.containsKey(key)) return null
            out[key] = obj
        }
        return out
    }

    private fun stableKey(obj: JSONObject, fields: List<String>): String? {
        val parts = ArrayList<String>(fields.size)
        fields.forEach { field ->
            if (!obj.has(field) || obj.isNull(field)) return null
            parts.add(obj.get(field).toString())
        }
        return parts.joinToString("|")
    }

    private fun jsonEqual(left: Any?, right: Any?): Boolean {
        if (left === right) return true
        if (left == null || right == null) return false
        return when {
            left === JSONObject.NULL && right === JSONObject.NULL -> true
            left is JSONObject && right is JSONObject -> left.toString() == right.toString()
            left is JSONArray && right is JSONArray -> left.toString() == right.toString()
            else -> left == right
        }
    }
}
