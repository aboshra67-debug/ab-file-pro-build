package com.abfilepro.app.assistant

import android.content.Context
import java.time.LocalDate

class PrayerCompletionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mark(date: LocalDate, prayer: PrayerName, prayed: Boolean) {
        prefs.edit().putString(key(date, prayer), if (prayed) "yes" else "no").apply()
    }

    fun answer(date: LocalDate, prayer: PrayerName): Boolean? = when (prefs.getString(key(date, prayer), null)) {
        "yes" -> true
        "no" -> false
        else -> null
    }

    private fun key(date: LocalDate, prayer: PrayerName): String = "${date}_${prayer.storageId}"

    companion object { private const val PREFS = "ab_prayer_completion_v1" }
}
