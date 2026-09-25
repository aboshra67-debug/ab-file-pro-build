package com.abfilepro.app.assistant

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class DocumentKind(val storageId: String, val arabicName: String) {
    NATIONAL_ID("national_id", "بطاقة الرقم القومي"),
    DRIVER_LICENSE("driver_license", "رخصة القيادة"),
    PASSPORT("passport", "جواز السفر"),
    CAR_INSURANCE("car_insurance", "تأمين السيارة"),
    OTHER("other", "مستند آخر");

    companion object {
        fun fromStorageId(value: String): DocumentKind = entries.firstOrNull { it.storageId == value } ?: OTHER
    }
}

enum class DocumentExpiryStatus { VALID, SOON, EXPIRED }

data class DocumentEntryMeta(
    val kind: DocumentKind = DocumentKind.OTHER,
    val issuedDate: String = "",
)

object DocumentRenewalCodec {
    private const val DEFAULT_LEAD_DAYS = 30

    fun encode(meta: DocumentEntryMeta): String = buildString {
        append("type=").append(meta.kind.storageId)
        if (meta.issuedDate.isNotBlank()) append("|issued=").append(meta.issuedDate.take(20))
    }

    fun parse(value: String): DocumentEntryMeta {
        val parts = value.split('|').mapNotNull { raw ->
            val idx = raw.indexOf('=')
            if (idx <= 0) null else raw.substring(0, idx) to raw.substring(idx + 1)
        }.toMap()
        return DocumentEntryMeta(
            kind = DocumentKind.fromStorageId(parts["type"].orEmpty()),
            issuedDate = parts["issued"].orEmpty().take(20),
        )
    }

    fun leadDays(entry: AssistantEntry): Int = (entry.amount ?: DEFAULT_LEAD_DAYS.toDouble()).toInt().coerceIn(0, 365)

    fun expiryDate(entry: AssistantEntry, zoneId: ZoneId = ZoneId.systemDefault()): LocalDate? =
        entry.dueAt?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() }

    fun daysUntilExpiry(entry: AssistantEntry, today: LocalDate = LocalDate.now()): Long? =
        expiryDate(entry)?.let { java.time.temporal.ChronoUnit.DAYS.between(today, it) }

    fun status(entry: AssistantEntry, today: LocalDate = LocalDate.now()): DocumentExpiryStatus {
        val days = daysUntilExpiry(entry, today) ?: return DocumentExpiryStatus.VALID
        return when {
            days < 0 -> DocumentExpiryStatus.EXPIRED
            days <= leadDays(entry) -> DocumentExpiryStatus.SOON
            else -> DocumentExpiryStatus.VALID
        }
    }

    fun expiryAtNine(date: LocalDate, zoneId: ZoneId = ZoneId.systemDefault()): Long =
        date.atTime(9, 0).atZone(zoneId).toInstant().toEpochMilli()
}
