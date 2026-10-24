package com.abfilepro.app.assistant

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

enum class TodayKind { TASK, EVENT, MEDICINE, HEALTH_APPOINTMENT, BILL, DOCUMENT }

data class TodayItem(
    val key: String,
    val kind: TodayKind,
    val title: String,
    val atMillis: Long?,
    val done: Boolean = false,
    /** For tasks/events: the record the screen can complete or open. */
    val entryId: String? = null,
    val detail: String = "",
)

data class TodayAgenda(
    val overdue: List<TodayItem>,
    val today: List<TodayItem>,
    val soon: List<TodayItem>,
    val undatedTasks: Int,
) {
    val isEmpty: Boolean get() = overdue.isEmpty() && today.isEmpty() && soon.isEmpty()
    val pendingToday: Int get() = today.count { !it.done }
}

/**
 * P27: one list of what matters today, assembled from tasks, appointments, medicine doses, bills and
 * documents. Pure (no Android), so it is JVM-tested.
 */
object TodayAgendaBuilder {
    private const val SOON_DAYS = 3L
    private const val DOCUMENT_DAYS = 14L

    fun build(
        entries: List<AssistantEntry>,
        bills: List<BillReminderRecord>,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): TodayAgenda {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val today = now.toLocalDate()
        val startOfToday = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val startOfTomorrow = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val soonEnd = today.plusDays(SOON_DAYS + 1).atStartOfDay(zone).toInstant().toEpochMilli()
        fun dayOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

        val overdue = mutableListOf<TodayItem>()
        val todayItems = mutableListOf<TodayItem>()
        val soon = mutableListOf<TodayItem>()
        var undated = 0

        entries.forEach { entry ->
            when (entry.module) {
                AssistantModule.TASKS -> {
                    if (entry.completed) return@forEach
                    val due = entry.dueAt
                    if (due == null) { undated++; return@forEach }
                    val item = TodayItem("task:${entry.id}", TodayKind.TASK, entry.title, due, entryId = entry.id, detail = entry.details)
                    when {
                        due < startOfToday -> overdue += item
                        due < startOfTomorrow -> todayItems += item
                        due < soonEnd -> soon += item
                    }
                }
                AssistantModule.CALENDAR -> {
                    val due = entry.dueAt ?: return@forEach
                    val item = TodayItem("event:${entry.id}", TodayKind.EVENT, entry.title, due, done = entry.completed, entryId = entry.id, detail = entry.details)
                    when {
                        due in startOfToday until startOfTomorrow -> todayItems += item
                        due in startOfTomorrow until soonEnd && !entry.completed -> soon += item
                    }
                }
                AssistantModule.HEALTH -> {
                    if (!entry.enabled) return@forEach
                    val meta = HealthEntryCodec.parse(entry.secondary)
                    val who = when (meta.owner) { HealthOwner.MEMBER -> meta.member; else -> "" }
                    if (meta.type == HealthEntryType.MEDICATION) {
                        val takenToday = if (meta.lastTakenDate == today.toString()) meta.takenCount else 0
                        meta.doseTimes.forEachIndexed { index, text ->
                            val time = runCatching { LocalTime.parse(text) }.getOrNull() ?: return@forEachIndexed
                            val at = ZonedDateTime.of(today, time, zone).toInstant().toEpochMilli()
                            todayItems += TodayItem("dose:${entry.id}:$index", TodayKind.MEDICINE, entry.title, at, done = index < takenToday, entryId = entry.id, detail = who)
                        }
                    } else {
                        val due = entry.dueAt ?: return@forEach
                        val item = TodayItem("health:${entry.id}", TodayKind.HEALTH_APPOINTMENT, entry.title, due, done = entry.completed, entryId = entry.id, detail = who)
                        when {
                            due in startOfToday until startOfTomorrow -> todayItems += item
                            due in startOfTomorrow until soonEnd && !entry.completed -> soon += item
                        }
                    }
                }
                AssistantModule.DOCUMENTS -> {
                    val due = entry.dueAt ?: return@forEach
                    val days = java.time.temporal.ChronoUnit.DAYS.between(today, dayOf(due))
                    if (days in 0..DOCUMENT_DAYS) soon += TodayItem("doc:${entry.id}", TodayKind.DOCUMENT, entry.title, due, entryId = entry.id)
                }
                else -> Unit
            }
        }
        bills.forEach { bill ->
            val days = java.time.temporal.ChronoUnit.DAYS.between(today, dayOf(bill.dueAt))
            val item = TodayItem("bill:${bill.id}", TodayKind.BILL, bill.type, bill.dueAt, detail = bill.account)
            when {
                days < 0 -> overdue += item
                days == 0L -> todayItems += item
                days <= SOON_DAYS -> soon += item
            }
        }
        val byTime = compareBy<TodayItem> { it.atMillis ?: Long.MAX_VALUE }
        return TodayAgenda(overdue.sortedWith(byTime), todayItems.sortedWith(byTime), soon.sortedWith(byTime), undated)
    }
}
