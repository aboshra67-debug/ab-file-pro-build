package com.abfilepro.app.assistant

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * P27: turns one typed sentence (Egyptian or standard Arabic, also English words for the common
 * cases) into a task or appointment: "ذكرني بكرة الساعة 5 العصر أكلم أحمد".
 * Pure and bounded; it never guesses a date it did not read in the text.
 */
object QuickAddParser {
    data class Result(
        val title: String,
        val dueAt: Long?,
        val calendar: Boolean,
        val recurrence: Recurrence,
    )

    private val weekdays = mapOf(
        "السبت" to DayOfWeek.SATURDAY, "الاحد" to DayOfWeek.SUNDAY, "الاثنين" to DayOfWeek.MONDAY, "الاتنين" to DayOfWeek.MONDAY,
        "الثلاثاء" to DayOfWeek.TUESDAY, "التلات" to DayOfWeek.TUESDAY, "التلاتاء" to DayOfWeek.TUESDAY,
        "الاربعاء" to DayOfWeek.WEDNESDAY, "الاربع" to DayOfWeek.WEDNESDAY,
        "الخميس" to DayOfWeek.THURSDAY, "الجمعه" to DayOfWeek.FRIDAY, "الجمعة" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY, "sunday" to DayOfWeek.SUNDAY, "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY,
    )
    private val morningWords = setOf("الصبح", "صباحا", "الفجر", "am")
    private val eveningWords = setOf("العصر", "المغرب", "العشا", "العشاء", "بالليل", "الليل", "مساء", "المسا", "pm")
    private val noonWords = setOf("الظهر", "ظهرا")
    private val commandWords = setOf("ذكرني", "فكرني", "ذكريني", "فكريني", "عايز", "عاوز", "عايزه", "عاوزه", "اعمل", "ضيف", "اضف", "تذكير", "ان", "اني", "remind", "me", "to")
    private val calendarWords = setOf("موعد", "ميعاد", "اجتماع", "مقابله", "محاضره", "حجز", "meeting", "appointment")

    /** "الخميس" and the bare "خميس" (as in "كل خميس") both name a weekday. */
    private fun weekdayOf(word: String?): DayOfWeek? = if (word == null) null else weekdays[word] ?: weekdays["ال$word"]

    fun normalize(token: String): String {
        val sb = StringBuilder()
        for (ch in token.trim().lowercase()) {
            when {
                ch in 'ً'..'ٟ' || ch == 'ـ' -> Unit // tashkeel, tatweel
                ch in '٠'..'٩' -> sb.append('0' + (ch - '٠'))
                ch in '۰'..'۹' -> sb.append('0' + (ch - '۰'))
                ch == 'أ' || ch == 'إ' || ch == 'آ' -> sb.append('ا')
                ch == 'ى' -> sb.append('ي')
                ch == 'ة' -> sb.append('ه')
                ch == '،' || ch == ',' || ch == '.' || ch == '؟' || ch == '!' -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun parse(input: String, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Result {
        val raw = input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (raw.isEmpty()) return Result("", null, false, Recurrence.NONE)
        val norm = raw.map(::normalize)
        val used = BooleanArray(raw.size)
        fun mark(from: Int, count: Int = 1) { for (k in from until minOf(from + count, raw.size)) used[k] = true }
        fun number(i: Int): Int? = norm.getOrNull(i)?.takeIf { it.matches(Regex("\\d{1,2}")) }?.toInt()
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)

        var recurrence = Recurrence.NONE
        var weekday: DayOfWeek? = null
        var dayOffset: Int? = null
        var relativeMinutes: Long? = null
        var hour: Int? = null
        var minute = 0
        var period: String? = null // "am", "pm", "noon"

        // 1) repetition: كل يوم / كل أسبوع / كل شهر / كل خميس
        var i = 0
        while (i < norm.size) {
            if (norm[i] == "كل" || norm[i] == "every") {
                val a = norm.getOrNull(i + 1)
                val b = norm.getOrNull(i + 2)
                when {
                    a == "يوم" && weekdayOf(b) != null -> { recurrence = Recurrence.WEEKLY; weekday = weekdayOf(b); mark(i, 3) }
                    a == "يوم" || a == "day" -> { recurrence = Recurrence.DAILY; mark(i, 2) }
                    a == "اسبوع" || a == "week" -> { recurrence = Recurrence.WEEKLY; mark(i, 2) }
                    a == "شهر" || a == "month" -> { recurrence = Recurrence.MONTHLY; mark(i, 2) }
                    weekdayOf(a) != null -> { recurrence = Recurrence.WEEKLY; weekday = weekdayOf(a); mark(i, 2) }
                }
            }
            i++
        }

        // 2) "بعد بكرة" and relative offsets (بعد ساعتين / بعد 3 أيام / بعد ربع ساعة)
        i = 0
        while (i < norm.size) {
            if (!used[i] && norm[i] == "بعد") {
                val a = norm.getOrNull(i + 1)
                val b = norm.getOrNull(i + 2)
                val n = number(i + 1)
                when {
                    a == "بكره" || a == "بكرا" -> { dayOffset = 2; mark(i, 2) }
                    n != null && b != null && (b.startsWith("ساع")) -> { relativeMinutes = n * 60L; mark(i, 3) }
                    n != null && b != null && (b.startsWith("دقيق") || b.startsWith("دقايق")) -> { relativeMinutes = n.toLong(); mark(i, 3) }
                    n != null && b != null && (b == "يوم" || b.startsWith("ايام")) -> { dayOffset = (dayOffset ?: 0) + n; mark(i, 3) }
                    n != null && b != null && b.startsWith("اسبوع") -> { dayOffset = (dayOffset ?: 0) + n * 7; mark(i, 3) }
                    a == "ساعه" -> { relativeMinutes = 60; mark(i, 2) }
                    a == "ساعتين" -> { relativeMinutes = 120; mark(i, 2) }
                    a == "ربع" && b?.startsWith("ساع") == true -> { relativeMinutes = 15; mark(i, 3) }
                    a == "نص" && b?.startsWith("ساع") == true -> { relativeMinutes = 30; mark(i, 3) }
                    a == "دقيقه" -> { relativeMinutes = 1; mark(i, 2) }
                    a == "يوم" -> { dayOffset = 1; mark(i, 2) }
                    a == "يومين" -> { dayOffset = 2; mark(i, 2) }
                    a == "اسبوع" -> { dayOffset = 7; mark(i, 2) }
                    a == "اسبوعين" -> { dayOffset = 14; mark(i, 2) }
                }
            }
            i++
        }

        // 3) day words
        i = 0
        while (i < norm.size) {
            if (!used[i]) {
                val w = norm[i]
                when {
                    w == "النهارده" || w == "النهارض" || w == "اليوم" || w == "today" -> { dayOffset = 0; mark(i) }
                    w == "بكره" || w == "بكرا" || w == "غدا" || w == "tomorrow" -> { dayOffset = 1; mark(i) }
                    weekdays.containsKey(w) && recurrence == Recurrence.NONE -> {
                        weekday = weekdays[w]
                        mark(i)
                        if (i > 0 && !used[i - 1] && norm[i - 1] == "يوم") mark(i - 1)
                    }
                }
            }
            i++
        }

        // 4) time of day
        fun periodOf(word: String?): String? = when (word) {
            in morningWords -> "am"
            in eveningWords -> "pm"
            in noonWords -> "noon"
            else -> null
        }
        i = 0
        while (i < norm.size && hour == null) {
            if (used[i]) { i++; continue }
            val w = norm[i]
            val clock = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(w)
            var h: Int? = null
            var consumed = 1
            when {
                clock != null -> { h = clock.groupValues[1].toInt(); minute = clock.groupValues[2].toInt() }
                (w == "الساعه" || w == "الساعة" || w == "ساعه") && number(i + 1) != null -> { h = number(i + 1); consumed = 2 }
                w.startsWith("ال") && w.length in 3..4 && w.drop(2).matches(Regex("\\d{1,2}")) -> h = w.drop(2).toInt()
                w.matches(Regex("\\d{1,2}")) -> {
                    val next = norm.getOrNull(i + 1)
                    val nextNext = norm.getOrNull(i + 2)
                    if (periodOf(next) != null || next == "ونص" || next == "وربع" || next == "وثلث" || (next == "الا" && (nextNext == "ربع" || nextNext == "ثلث"))) h = w.toInt()
                }
            }
            if (h != null && h in 0..24) {
                hour = h % 24
                mark(i, consumed)
                val after = i + consumed
                val t1 = norm.getOrNull(after)
                val t2 = norm.getOrNull(after + 1)
                var extra = 0
                when {
                    t1 == "ونص" -> { minute = 30; extra = 1 }
                    t1 == "وربع" -> { minute = 15; extra = 1 }
                    t1 == "وثلث" -> { minute = 20; extra = 1 }
                    t1 == "الا" && t2 == "ربع" -> { hour = (hour!! + 23) % 24; minute = 45; extra = 2 }
                    t1 == "الا" && t2 == "ثلث" -> { hour = (hour!! + 23) % 24; minute = 40; extra = 2 }
                }
                mark(after, extra)
                val p = periodOf(norm.getOrNull(after + extra))
                if (p != null) { period = p; mark(after + extra) }
                // "الساعة 5 ونص" style handled; "في" before the clock is just filler
            }
            i++
        }
        // period word alone ("الظهر", "بالليل") gives a default hour
        if (hour == null) {
            for (k in norm.indices) {
                if (used[k]) continue
                val defaultHour = when (norm[k]) {
                    "الصبح", "صباحا" -> 8; "الظهر", "ظهرا" -> 12; "العصر" -> 16; "المغرب" -> 18
                    "العشا", "العشاء" -> 20; "الفجر" -> 5; "بالليل", "الليل" -> 21; "مساء", "المسا" -> 19
                    else -> null
                }
                if (defaultHour != null) { hour = defaultHour; period = "exact"; mark(k); break }
            }
        }

        // 5) due date
        var due: Long? = null
        val today = now.toLocalDate()
        fun at(date: LocalDate, h: Int, m: Int): ZonedDateTime = ZonedDateTime.of(date, LocalTime.of(h.coerceIn(0, 23), m.coerceIn(0, 59)), zone)
        fun resolveHours(h: Int): List<Int> = when (period) {
            "am" -> listOf(if (h == 12) 0 else h)
            "pm" -> listOf(if (h < 12) h + 12 else h)
            "noon" -> listOf(if (h in 1..5) h + 12 else h)
            "exact" -> listOf(h)
            else -> if (h in 1..12) listOf(h % 12, h % 12 + 12).distinct().sorted() else listOf(h)
        }
        when {
            relativeMinutes != null -> due = nowMillis + relativeMinutes * 60_000L
            dayOffset != null || weekday != null || hour != null || recurrence != Recurrence.NONE -> {
                val h = hour
                val candidates = if (h != null) resolveHours(h) else listOf(9)
                val m = if (h != null) minute else 0
                val date: LocalDate = when {
                    weekday != null -> {
                        var ahead = ((weekday.value - today.dayOfWeek.value) + 7) % 7
                        val sameDay = ahead == 0
                        val timePassed = candidates.all { at(today, it, m).toInstant().toEpochMilli() <= nowMillis }
                        if (sameDay && (h == null || timePassed)) ahead = 7
                        today.plusDays(ahead.toLong())
                    }
                    dayOffset != null -> today.plusDays(dayOffset.toLong())
                    else -> today
                }
                val explicitDay = weekday != null || dayOffset != null
                var picked: ZonedDateTime? = candidates.map { at(date, it, m) }.firstOrNull { it.toInstant().toEpochMilli() > nowMillis }
                if (picked == null) {
                    picked = if (explicitDay) at(date, candidates.first(), m) else at(date.plusDays(1), candidates.first(), m)
                }
                due = picked.toInstant().toEpochMilli()
            }
        }

        // 6) title: everything not consumed, minus filler command words
        val kept = raw.indices.filter { idx -> !used[idx] && norm[idx] !in commandWords }
        var title = kept.joinToString(" ") { raw[it] }.trim()
        if (title.isEmpty()) title = raw.filterIndexed { idx, _ -> norm[idx] !in commandWords }.joinToString(" ").trim().ifEmpty { input.trim() }
        val calendar = norm.any { it in calendarWords }
        return Result(title.take(240), due, calendar, recurrence)
    }
}
