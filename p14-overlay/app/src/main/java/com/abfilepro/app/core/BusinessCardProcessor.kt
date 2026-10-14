package com.abfilepro.app.core

import android.content.Context
import android.net.Uri
import com.abfilepro.app.ui.i18n.uiText
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

object BusinessCardProcessor {
    data class ParsedData(
        val name: String = "",
        val company: String = "",
        val phone: String = "",
        val email: String = "",
        val website: String = "",
        val confidence: Int = 0,
        val rawText: String = ""
    )

    fun cacheSource(context: Context, uri: Uri): File {
        val dir = File(context.cacheDir, "business_card_scan/${System.currentTimeMillis()}").apply { mkdirs() }
        val out = File(dir, "source.jpg")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "تعذر قراءة صورة بطاقة العمل" }
            FileOutputStream(out).use { output -> input.copyTo(output) }
        }
        require(out.length() > 0L) { "تعذر تجهيز صورة بطاقة العمل" }
        return out
    }

    fun parse(text: String): ParsedData {
        val clean = text.replace('\u00A0', ' ').trim()
        if (clean.isBlank()) return ParsedData(rawText = clean)
        val lines = clean.lines()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }

        val email = findEmail(clean)
        val website = findWebsite(lines, email)
        val phone = findPhone(lines)
        val name = findName(lines, email, website, phone)
        val company = findCompany(lines, name, email, website, phone)

        var score = 18
        if (name.isNotBlank()) score += 25
        if (company.isNotBlank()) score += 16
        if (phone.isNotBlank()) score += 18
        if (email.isNotBlank()) score += 16
        if (website.isNotBlank()) score += 7

        return ParsedData(
            name = name,
            company = company,
            phone = phone,
            email = email,
            website = website,
            confidence = score.coerceAtMost(98),
            rawText = clean
        )
    }

    fun structuredText(context: Context, languageCode: String, data: ParsedData): String = buildString {
        appendLine(context.uiText(languageCode, "b2_export_pair", context.uiText(languageCode, "b2_business_name"), data.name.ifBlank { "-" }))
        appendLine(context.uiText(languageCode, "b2_export_pair", context.uiText(languageCode, "b2_business_company"), data.company.ifBlank { "-" }))
        appendLine(context.uiText(languageCode, "b2_export_pair", context.uiText(languageCode, "b2_business_phone"), data.phone.ifBlank { "-" }))
        appendLine(context.uiText(languageCode, "b2_export_pair", context.uiText(languageCode, "b2_business_email"), data.email.ifBlank { "-" }))
        appendLine(context.uiText(languageCode, "b2_export_pair", context.uiText(languageCode, "b2_business_website"), data.website.ifBlank { "-" }))
        appendLine(context.uiText(languageCode, "b2_business_conf", data.confidence))
    }

    fun cleanup(file: File?) {
        file?.parentFile?.takeIf { it.parentFile?.name == "business_card_scan" }?.let {
            runCatching { it.deleteRecursively() }
        }
    }

    private fun findEmail(text: String): String {
        val regex = Regex("(?i)(?<![A-Z0-9._%+\\-])[A-Z0-9._%+\\-]{1,64}@[A-Z0-9.\\-]+\\.[A-Z]{2,24}(?![A-Z0-9._%+\\-])")
        return regex.find(text)?.value?.trim().orEmpty()
    }

    private fun findWebsite(lines: List<String>, email: String): String {
        val urlRegex = Regex("(?i)(?:https?://)?(?:www\\.)?[a-z0-9][a-z0-9\\-]{0,62}(?:\\.[a-z0-9][a-z0-9\\-]{0,62})+[/a-z0-9._~:/?#\\[\\]@!$&'()*+,;=%\\-]*")
        for (line in lines) {
            for (match in urlRegex.findAll(line)) {
                val value = match.value.trim().trimEnd('.', ',', ';', ':')
                if (value.contains('@') || (email.isNotBlank() && email.contains(value, ignoreCase = true))) continue
                val lower = value.lowercase(Locale.ROOT)
                if (lower.endsWith(".com") || lower.contains(".com/") || lower.contains(".net") || lower.contains(".org") ||
                    lower.contains(".io") || lower.contains(".co") || lower.contains(".eg") || lower.contains(".sa") ||
                    lower.startsWith("www.") || lower.startsWith("http")) return value
            }
        }
        return ""
    }

    private fun findPhone(lines: List<String>): String {
        val phoneRegex = Regex("(?:\\+?[0-9٠-٩۰-۹][0-9٠-٩۰-۹\\s().\\-]{5,}[0-9٠-٩۰-۹])")
        val ordered = lines.sortedByDescending { line ->
            val lower = line.lowercase(Locale.ROOT)
            if (containsAny(lower, "phone", "mobile", "mob", "tel", "هاتف", "موبايل", "جوال", "تليفون")) 1 else 0
        }
        for (line in ordered) {
            for (match in phoneRegex.findAll(normalizeDigits(line))) {
                val value = match.value.trim()
                val digits = value.count { it.isDigit() }
                if (digits in 7..16) return value.replace(Regex("\\s+"), " ")
            }
        }
        return ""
    }

    private fun findName(lines: List<String>, email: String, website: String, phone: String): String {
        val companyTerms = companyTerms()
        val roleTerms = listOf("manager", "director", "engineer", "doctor", "dr.", "sales", "marketing", "ceo", "owner", "مدير", "مهندس", "دكتور", "مبيعات", "تسويق", "رئيس")
        return lines.take(9)
            .filter { line ->
                if (line.length !in 3..55) return@filter false
                if ((email.isNotBlank() && line.contains(email, ignoreCase = true)) ||
                    (website.isNotBlank() && line.contains(website, ignoreCase = true)) ||
                    (phone.isNotBlank() && line.contains(phone))) return@filter false
                val lower = line.lowercase(Locale.ROOT)
                if (line.contains('@') || lower.contains("www.") || lower.contains("http")) return@filter false
                if (companyTerms.any { lower.contains(it) } || roleTerms.any { lower.contains(it) }) return@filter false
                val digits = line.count { it.isDigit() }
                val letters = line.count { it.isLetter() }
                val words = line.split(Regex("\\s+")).count { it.isNotBlank() }
                letters >= 3 && digits <= 2 && words in 1..6
            }
            .maxByOrNull { line ->
                val index = lines.indexOf(line).coerceAtLeast(0)
                val words = line.split(Regex("\\s+")).size
                val early = (9 - index) * 5
                val nameShape = if (words in 2..4) 18 else 5
                early + nameShape + line.count { it.isLetter() }
            }
            .orEmpty()
    }

    private fun findCompany(lines: List<String>, name: String, email: String, website: String, phone: String): String {
        val terms = companyTerms()
        lines.take(12).firstOrNull { line ->
            val lower = line.lowercase(Locale.ROOT)
            line != name && terms.any { lower.contains(it) }
        }?.let { return it }

        val nameIndex = lines.indexOf(name)
        val candidates = lines.take(10).filter { line ->
            if (line == name || (email.isNotBlank() && line.contains(email, ignoreCase = true)) ||
                (website.isNotBlank() && line.contains(website, ignoreCase = true)) ||
                (phone.isNotBlank() && line.contains(phone))) return@filter false
            val lower = line.lowercase(Locale.ROOT)
            if (line.contains('@') || lower.contains("www.") || lower.contains("http")) return@filter false
            val letters = line.count { it.isLetter() }
            val digits = line.count { it.isDigit() }
            letters >= 3 && digits <= 3 && line.length in 3..70
        }
        return candidates.maxByOrNull { line ->
            val index = lines.indexOf(line).coerceAtLeast(0)
            val nearName = if (nameIndex >= 0) (16 - kotlin.math.abs(index - nameIndex) * 4).coerceAtLeast(0) else 0
            nearName + line.count { it.isLetter() }
        }.orEmpty()
    }

    private fun companyTerms(): List<String> = listOf(
        "company", "co.", "corp", "corporation", "group", "llc", "l.l.c", "ltd", "limited", "inc", "clinic", "center", "centre", "university", "school", "studio", "solutions", "services",
        "شركة", "مؤسسة", "مجموعة", "مركز", "عيادة", "جامعة", "مدرسة", "مكتب", "خدمات", "حلول"
    )

    private fun normalizeDigits(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            append(
                when (ch) {
                    '٠', '۰' -> '0'; '١', '۱' -> '1'; '٢', '۲' -> '2'; '٣', '۳' -> '3'; '٤', '۴' -> '4'
                    '٥', '۵' -> '5'; '٦', '۶' -> '6'; '٧', '۷' -> '7'; '٨', '۸' -> '8'; '٩', '۹' -> '9'
                    else -> ch
                }
            )
        }
    }

    private fun containsAny(text: String, vararg values: String): Boolean = values.any { text.contains(it) }
}
