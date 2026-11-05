package com.gernalix.personalhub.soldi

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

internal data class GmailAppointment(
    val uid: String,
    val messageId: String,
    val title: String,
    val startsAt: Instant,
    val location: String,
    val sequence: Int,
    val cancelled: Boolean,
    val allDay: Boolean,
    val dateOnly: LocalDate? = null,
)
internal object GmailCalendarParser {
    fun parse(message: JSONObject, previous: List<GmailAppointment> = emptyList()): List<GmailAppointment> {
        val calendars = mutableListOf<String>()
        fun visit(part: JSONObject, depth: Int) {
            require(depth <= 12)
            if (part.optString("mimeType") == "text/calendar") part.optJSONObject("body")?.text("data")?.let {
                require(it.length <= 512 * 1024)
                calendars += Base64.getUrlDecoder().decode(it).toString(Charsets.UTF_8)
            }
            val parts = part.optJSONArray("parts") ?: return
            require(parts.length() <= 100)
            for (i in 0 until parts.length()) visit(parts.getJSONObject(i), depth + 1)
        }
        visit(message.getJSONObject("payload"), 0)
        return calendars.flatMap { parseIcs(it, message.getString("id"), previous) }
    }
    fun parseIcs(text: String, messageId: String, previous: List<GmailAppointment> = emptyList()): List<GmailAppointment> {
        require(text.length <= 512 * 1024)
        val unfolded = text.replace("\r\n", "\n").replace(Regex("\n[ \t]"), "")
        val cancelledMethod = Regex("(?m)^METHOD:CANCEL$").containsMatchIn(unfolded)
        return Regex("(?s)BEGIN:VEVENT\n(.*?)\nEND:VEVENT").findAll(unfolded).mapNotNull { event ->
            val fields = event.groupValues[1].lines().mapNotNull { line ->
                val colon = line.indexOf(':'); if (colon < 0) null else line.substring(0, colon) to line.substring(colon + 1)
            }
            fun value(name: String) = fields.filter { it.first.substringBefore(';') == name }.singleOrNull()?.second
            fun unescape(value: String) = value.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
            val uid = value("UID")?.takeIf { it.length in 1..500 } ?: return@mapNotNull null
            // Recurrence instances share UID: include RECURRENCE-ID in the stable identity.
            val identity = uid + (value("RECURRENCE-ID")?.let { "|$it" } ?: "")
            val cancelled = cancelledMethod || value("STATUS") == "CANCELLED"
            val prior = previous.filter { it.uid == identity }.maxByOrNull { it.sequence }
            val sequence = value("SEQUENCE")?.toIntOrNull() ?: 0
            if (cancelled && prior != null) return@mapNotNull prior.copy(messageId = messageId, cancelled = true, sequence = sequence)
            val title = value("SUMMARY")?.let(::unescape)?.take(500)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val start = fields.filter { it.first.substringBefore(';') == "DTSTART" }.singleOrNull() ?: return@mapNotNull null
            val allDay = start.first.contains("VALUE=DATE")
            val time = runCatching {
                when {
                    allDay -> LocalDate.parse(start.second, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(ZoneId.systemDefault()).toInstant()
                    start.second.endsWith("Z") -> LocalDateTime.parse(start.second.dropLast(1), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).toInstant(ZoneOffset.UTC)
                    start.first.contains("TZID=") -> {
                        val zone = start.first.substringAfter("TZID=").substringBefore(';').trim('"')
                        val local = LocalDateTime.parse(start.second, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                        val offsets = ZoneId.of(zone).rules.getValidOffsets(local)
                        require(offsets.size == 1) // DST gaps and overlaps need review, never shift the supplied time
                        local.toInstant(offsets.single())
                    }
                    else -> null // floating times are ambiguous; never invent a timezone
                }
            }.getOrNull() ?: return@mapNotNull null
            GmailAppointment(identity, messageId, title, time, value("LOCATION")?.let(::unescape)?.take(500).orEmpty(),
                sequence, cancelled, allDay, if (allDay) LocalDate.parse(start.second, DateTimeFormatter.BASIC_ISO_DATE) else null)
        }.toList()
    }
}
