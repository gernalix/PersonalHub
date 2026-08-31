// v395
package com.example.multitimetracker.export

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import kotlin.math.max

/**
 * v395 – Snapshot retention policy (anti-disk-explosion).
 *
 * Policy (simple, robust):
 * - Keep all snapshots from last 48h
 * - Keep 1/day for 14 days
 * - Keep 1/week for 8 weeks
 * - Keep 1/month for 12 months
 *
 * Manual snapshots are supported via filename marker "manual-" in reason.
 */
object VaultRetention {

    // Filename: vault_v<ver>_YYYYMMDD_HHMMSS_reason-<reason>.db
    private val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun pruneSnapshots(context: Context, snapshotsDir: DocumentFile) {
        val now = System.currentTimeMillis()
        val items = snapshotsDir.listFiles()
            .filter { it.isFile }
            .mapNotNull { doc ->
                val name = doc.name ?: return@mapNotNull null
                val ms = parseMsFromName(name) ?: return@mapNotNull null
                Triple(doc, name, ms)
            }
            .sortedByDescending { it.third }

        if (items.isEmpty()) return

        val keep = HashSet<String>()

        // Always keep anything explicitly "manual"
        items.filter { it.second.contains("reason-manual", ignoreCase = true) }
            .forEach { keep.add(it.second) }

        // 48h: keep all
        val keep48h = now - 48L * 60L * 60L * 1000L
        items.filter { it.third >= keep48h }.forEach { keep.add(it.second) }

        // 1/day for 14d
        val start14 = now - 14L * 24L * 60L * 60L * 1000L
        keepBuckets(items, keep, start14, now, bucket = Bucket.DAY)

        // 1/week for 8w
        val start8w = now - 8L * 7L * 24L * 60L * 60L * 1000L
        keepBuckets(items, keep, start8w, now, bucket = Bucket.WEEK)

        // 1/month for 12m (approx by month key from date)
        val start12m = now - 365L * 24L * 60L * 60L * 1000L
        keepBuckets(items, keep, start12m, now, bucket = Bucket.MONTH)

        // Delete everything else (best effort)
        items.filter { it.second !in keep }.forEach { (doc, _, _) ->
            runCatching { doc.delete() }
        }
    }

    private enum class Bucket { DAY, WEEK, MONTH }

    private fun keepBuckets(
        items: List<Triple<DocumentFile, String, Long>>,
        keep: MutableSet<String>,
        startMs: Long,
        endMs: Long,
        bucket: Bucket
    ) {
        val chosen = HashMap<String, String>() // bucketKey -> filename
        for ((_, name, ms) in items) {
            if (ms < startMs || ms > endMs) continue
            val key = bucketKey(ms, bucket)
            // items are sorted newest first: first time we see key is the newest in that bucket
            if (!chosen.containsKey(key)) chosen[key] = name
        }
        chosen.values.forEach { keep.add(it) }
    }

    private fun bucketKey(ms: Long, bucket: Bucket): String {
        val d = Date(ms)
        return when (bucket) {
            Bucket.DAY -> SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d)
            Bucket.WEEK -> {
                // ISO week is overkill; approximate by year + week-of-year (US locale).
                SimpleDateFormat("yyyy-ww", Locale.US).format(d)
            }
            Bucket.MONTH -> SimpleDateFormat("yyyy-MM", Locale.US).format(d)
        }
    }

    private fun parseMsFromName(name: String): Long? {
        val i = name.indexOf('_')
        if (i < 0) return null
        // expect: vault_v123_20260304_231512_reason-xxx.db  (date part after 2nd underscore)
        val parts = name.split("_")
        if (parts.size < 4) return null
        val datePart = parts[2] + "_" + parts[3].substring(0, 6) // HHmmss within parts[3] maybe includes rest
        val core = parts[2] + "_" + parts[3].take(6)
        return runCatching { fmt.parse(core)?.time }.getOrNull()
    }
}
