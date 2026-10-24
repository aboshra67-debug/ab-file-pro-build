package com.abfilepro.app.assistant

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

enum class HealthEntryType { MEDICATION, APPOINTMENT }
/** P25: MEMBER is any extra family member (child, parent...); the name travels in [HealthEntryMeta.member]. */
enum class HealthOwner { SELF, PARTNER, MEMBER }

data class HealthEntryMeta(
    val type: HealthEntryType = HealthEntryType.MEDICATION,
    val owner: HealthOwner = HealthOwner.SELF,
    val dailyTime: String = "08:00",
    val lastTakenDate: String = "",
    /** P25: further daily doses besides [dailyTime] (a medicine taken 2-4 times a day). */
    val moreTimes: List<String> = emptyList(),
    val member: String = "",
    /** Doses already taken on [lastTakenDate]. */
    val takenCount: Int = 0,
) {
    /** Every daily dose time, earliest first. */
    val doseTimes: List<String>
        get() = (listOf(dailyTime) + moreTimes).filter { it.matches(Regex("\\d{2}:\\d{2}")) }.distinct().sorted().ifEmpty { listOf("08:00") }
}

object HealthEntryCodec {
    /** A name must never contain the separators of this format. */
    fun cleanName(value: String): String = value.map { if (it == '|' || it == '=' || it == ',') ' ' else it }.joinToString("").trim().take(30)

    fun encode(meta: HealthEntryMeta): String = buildList {
        add("health")
        add(if (meta.type == HealthEntryType.MEDICATION) "med" else "appt")
        add("owner=${when (meta.owner) { HealthOwner.SELF -> "self"; HealthOwner.PARTNER -> "partner"; HealthOwner.MEMBER -> "member" }}")
        add("time=${meta.dailyTime.take(5)}")
        add("last=${meta.lastTakenDate.take(10)}")
        if (meta.moreTimes.isNotEmpty()) {
            add("more=${meta.moreTimes.joinToString(",") { it.take(5) }}")
            add("n=${meta.takenCount}")
        }
        if (meta.owner == HealthOwner.MEMBER && cleanName(meta.member).isNotEmpty()) add("who=${cleanName(meta.member)}")
    }.joinToString("|")

    fun parse(value: String): HealthEntryMeta {
        val parts = value.split('|')
        if (parts.firstOrNull() != "health") return HealthEntryMeta()
        val type = if (parts.getOrNull(1) == "appt") HealthEntryType.APPOINTMENT else HealthEntryType.MEDICATION
        val map = parts.drop(2).mapNotNull { token ->
            val i = token.indexOf('=')
            if (i <= 0) null else token.substring(0, i) to token.substring(i + 1)
        }.toMap()
        val more = map["more"].orEmpty().split(',').map { it.trim() }.filter { it.matches(Regex("\\d{2}:\\d{2}")) }
        val base = HealthEntryMeta(
            type = type,
            owner = when (map["owner"]) { "partner" -> HealthOwner.PARTNER; "member" -> HealthOwner.MEMBER; else -> HealthOwner.SELF },
            dailyTime = map["time"]?.takeIf { it.matches(Regex("\\d{2}:\\d{2}")) } ?: "08:00",
            lastTakenDate = map["last"].orEmpty().take(10),
            moreTimes = more,
            member = cleanName(map["who"].orEmpty()),
        )
        // Older entries have no counter: a recorded date meant "taken" (their only dose).
        val count = map["n"]?.toIntOrNull() ?: if (base.lastTakenDate.isNotBlank()) base.doseTimes.size else 0
        return base.copy(takenCount = count.coerceIn(0, base.doseTimes.size))
    }

    fun nextMedicationTrigger(time: LocalTime, fromMillis: Long = System.currentTimeMillis()): Long {
        val zone = ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(fromMillis).atZone(zone)
        var next = ZonedDateTime.of(now.toLocalDate(), time, zone)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return next.toInstant().toEpochMilli()
    }

    /** The soonest of the medicine's daily doses. */
    fun nextMedicationTrigger(meta: HealthEntryMeta, fromMillis: Long = System.currentTimeMillis()): Long =
        meta.doseTimes.map { text ->
            nextMedicationTrigger(runCatching { LocalTime.parse(text) }.getOrDefault(LocalTime.of(8, 0)), fromMillis)
        }.min()

    fun takenToday(meta: HealthEntryMeta, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val today = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        return meta.lastTakenDate == today && meta.takenCount >= meta.doseTimes.size
    }

    fun markTakenToday(meta: HealthEntryMeta, nowMillis: Long = System.currentTimeMillis()): HealthEntryMeta {
        val today = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val count = if (meta.lastTakenDate == today) meta.takenCount + 1 else 1
        return meta.copy(lastTakenDate = today, takenCount = count.coerceAtMost(meta.doseTimes.size))
    }

    fun todayString(): String = LocalDate.now().toString()
}
