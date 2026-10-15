package com.abfilepro.app.assistant

import java.time.LocalDate
import java.time.LocalTime

enum class AssistantCategory { ALL, PERSONAL, WORK }

enum class AssistantModule(
    val storageId: String,
    val categories: Set<AssistantCategory>,
) {
    PRAYER("prayer", setOf(AssistantCategory.PERSONAL, AssistantCategory.WORK)),
    NOTES("notes", setOf(AssistantCategory.PERSONAL, AssistantCategory.WORK)),
    TASKS("tasks", setOf(AssistantCategory.PERSONAL, AssistantCategory.WORK)),
    CALENDAR("calendar", setOf(AssistantCategory.PERSONAL, AssistantCategory.WORK)),
    MEETINGS("meetings", setOf(AssistantCategory.WORK)),
    VOICE("voice", setOf(AssistantCategory.PERSONAL, AssistantCategory.WORK)),
    CAMERA("camera", setOf(AssistantCategory.PERSONAL, AssistantCategory.WORK)),
    CALCULATOR("calculator", setOf(AssistantCategory.PERSONAL, AssistantCategory.WORK)),
    MONEY("money", setOf(AssistantCategory.PERSONAL, AssistantCategory.WORK)),
    MARKETS("markets", setOf(AssistantCategory.PERSONAL, AssistantCategory.WORK)),
    BILLS("bills", setOf(AssistantCategory.PERSONAL)),
    COMPARE("compare", setOf(AssistantCategory.PERSONAL)),
    SHOPPING("shopping", setOf(AssistantCategory.PERSONAL)),
    FAMILY_REQUESTS("family_requests", setOf(AssistantCategory.PERSONAL)),
    DOCUMENTS("documents", setOf(AssistantCategory.PERSONAL, AssistantCategory.WORK)),
    HEALTH("health", setOf(AssistantCategory.PERSONAL));

    companion object {
        fun fromStorageId(value: String): AssistantModule? = entries.firstOrNull { it.storageId == value }
    }
}

data class AssistantEntry(
    val id: String,
    val module: AssistantModule,
    val title: String,
    val details: String = "",
    val category: AssistantCategory = AssistantCategory.PERSONAL,
    val createdAt: Long = System.currentTimeMillis(),
    val dueAt: Long? = null,
    val completed: Boolean = false,
    val amount: Double? = null,
    val secondary: String = "",
    val enabled: Boolean = true,
    val pinned: Boolean = false,
)

data class DailySummary(
    val openTasks: Int,
    val appointmentsToday: Int,
    val pendingFamilyRequests: Int,
    val activeMedicationReminders: Int,
)

enum class PrayerName(val storageId: String) {
    FAJR("fajr"),
    SUNRISE("sunrise"),
    DHUHR("dhuhr"),
    ASR("asr"),
    MAGHRIB("maghrib"),
    ISHA("isha");

    companion object {
        fun fromStorageId(value: String): PrayerName? = entries.firstOrNull { it.storageId == value }
    }
}

enum class PrayerMethod(
    val fajrAngle: Double,
    val ishaAngle: Double,
) {
    EGYPTIAN(19.5, 17.5),
    MUSLIM_WORLD_LEAGUE(18.0, 17.0),
    UMM_AL_QURA(18.5, 18.5),
    KARACHI(18.0, 18.0),
}

enum class AsrSchool(val shadowFactor: Int) { STANDARD(1), HANAFI(2) }

enum class AdhanVoice { MINSHAWI, ABDUL_BASIT }

data class CityPreset(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val zoneId: String,
) {
    companion object {
        val CAIRO = CityPreset("cairo", 30.0444, 31.2357, "Africa/Cairo")
        val ALEXANDRIA = CityPreset("alexandria", 31.2001, 29.9187, "Africa/Cairo")
        val GIZA = CityPreset("giza", 30.0131, 31.2089, "Africa/Cairo")
        val RIYADH = CityPreset("riyadh", 24.7136, 46.6753, "Asia/Riyadh")
        val MECCA = CityPreset("mecca", 21.3891, 39.8579, "Asia/Riyadh")
        val DUBAI = CityPreset("dubai", 25.2048, 55.2708, "Asia/Dubai")
        val ALL = listOf(CAIRO, ALEXANDRIA, GIZA, RIYADH, MECCA, DUBAI)
        fun fromId(id: String): CityPreset = ALL.firstOrNull { it.id == id } ?: CAIRO
    }
}

data class PrayerSettings(
    val cityId: String = CityPreset.CAIRO.id,
    val latitude: Double = CityPreset.CAIRO.latitude,
    val longitude: Double = CityPreset.CAIRO.longitude,
    val zoneId: String = CityPreset.CAIRO.zoneId,
    val cityName: String = "القاهرة",
    val useDeviceLocation: Boolean = false,
    val method: PrayerMethod = PrayerMethod.EGYPTIAN,
    val asrSchool: AsrSchool = AsrSchool.STANDARD,
    val adjustmentsMinutes: Map<PrayerName, Int> = emptyMap(),
    val enabledAlerts: Set<PrayerName> = emptySet(),
    val preAlertMinutes: Int = 10,
    val exactAlarms: Boolean = true,
    val autoGps: Boolean = true,
    val adhanVoice: AdhanVoice = AdhanVoice.MINSHAWI,
    val prayerCheckMinutes: Int = 20,
    val repeatNudgeMinutes: Int = 10,
)

data class PrayerDay(
    val date: LocalDate,
    val times: Map<PrayerName, LocalTime>,
)
