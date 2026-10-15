package com.abfilepro.app.assistant

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

enum class HealthEntryType { MEDICATION, APPOINTMENT }
enum class HealthOwner { SELF, PARTNER }

data class HealthEntryMeta(
    val type: HealthEntryType = HealthEntryType.MEDICATION,
    val owner: HealthOwner = HealthOwner.SELF,
    val dailyTime: String = "08:00",
    val lastTakenDate: String = "",
)

object HealthEntryCodec {
    fun encode(meta: HealthEntryMeta): String = listOf(
        "health",
        if (meta.type == HealthEntryType.MEDICATION) "med" else "appt",
        "owner=${if (meta.owner == HealthOwner.SELF) "self" else "partner"}",
        "time=${meta.dailyTime.take(5)}",
        "last=${meta.lastTakenDate.take(10)}",
    ).joinToString("|")

    fun parse(value: String): HealthEntryMeta {
        val parts = value.split('|')
        if (parts.firstOrNull() != "health") return HealthEntryMeta()
        val type = if (parts.getOrNull(1) == "appt") HealthEntryType.APPOINTMENT else HealthEntryType.MEDICATION
        val map = parts.drop(2).mapNotNull { token ->
            val i = token.indexOf('=')
            if (i <= 0) null else token.substring(0, i) to token.substring(i + 1)
        }.toMap()
        return HealthEntryMeta(
            type = type,
            owner = if (map["owner"] == "partner") HealthOwner.PARTNER else HealthOwner.SELF,
            dailyTime = map["time"]?.takeIf { it.matches(Regex("\\d{2}:\\d{2}")) } ?: "08:00",
            lastTakenDate = map["last"].orEmpty().take(10),
        )
    }

    fun nextMedicationTrigger(time: LocalTime, fromMillis: Long = System.currentTimeMillis()): Long {
        val zone = ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(fromMillis).atZone(zone)
        var next = ZonedDateTime.of(now.toLocalDate(), time, zone)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return next.toInstant().toEpochMilli()
    }

    fun nextMedicationTrigger(meta: HealthEntryMeta, fromMillis: Long = System.currentTimeMillis()): Long {
        val time = runCatching { LocalTime.parse(meta.dailyTime) }.getOrDefault(LocalTime.of(8, 0))
        return nextMedicationTrigger(time, fromMillis)
    }

    fun takenToday(meta: HealthEntryMeta, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val today = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        return meta.lastTakenDate == today
    }

    fun markTakenToday(meta: HealthEntryMeta, nowMillis: Long = System.currentTimeMillis()): HealthEntryMeta {
        val today = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        return meta.copy(lastTakenDate = today)
    }

    fun todayString(): String = LocalDate.now().toString()
}
