package com.abfilepro.app.core

import android.content.Context
import android.net.Uri
import com.abfilepro.app.ui.i18n.uiText
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

object ReceiptInvoiceProcessor {
    data class ParsedData(
        val documentType: String = "financial_document",
        val vendor: String = "",
        val date: String = "",
        val invoiceNumber: String = "",
        val total: String = "",
        val tax: String = "",
        val confidence: Int = 0,
        val rawText: String = ""
    )

    fun cacheSource(context: Context, uri: Uri): File {
        val dir = File(context.cacheDir, "receipt_scan/${System.currentTimeMillis()}").apply { mkdirs() }
        val out = File(dir, "source.jpg")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "تعذر قراءة صورة الفاتورة" }
            FileOutputStream(out).use { output -> input.copyTo(output) }
        }
        require(out.length() > 0L) { "تعذر تجهيز صورة الفاتورة" }
        return out
    }

    fun parse(context: Context, languageCode: String, text: String): ParsedData {
        val cleanText = text.replace('\u00A0', ' ').trim()
        if (cleanText.isBlank()) return ParsedData(rawText = cleanText)
        val lines = cleanText.lines().map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotBlank() }
        val lowered = cleanText.lowercase(Locale.ROOT)

        val type = when {
            containsAny(lowered, "فاتورة", "invoice", "tax invoice", "فاتوره") -> context.uiText(languageCode, "b2_receipt_detected_invoice")
            containsAny(lowered, "إيصال", "ايصال", "receipt") -> context.uiText(languageCode, "b2_receipt_detected_receipt")
            else -> context.uiText(languageCode, "b2_receipt_detected_financial")
        }

        val invoiceNumber = findInvoiceNumber(lines)
        val date = findDate(lines)
        val total = findMoneyByKeywords(
            lines,
            listOf("grand total", "amount due", "total due", "الإجمالي النهائي", "الاجمالي النهائي", "إجمالي", "اجمالي", "المجموع", "total", "amount"),
            excludedKeywords = listOf("subtotal", "sub total", "قبل الضريبة", "before tax")
        )
        val tax = findMoneyByKeywords(
            lines,
            listOf("ضريبة القيمة المضافة", "القيمة المضافة", "ضريبة", "vat", "tax"),
            excludedKeywords = emptyList()
        )
        val vendor = findVendor(lines)

        var score = 22
        if (vendor.isNotBlank()) score += 16
        if (date.isNotBlank()) score += 14
        if (invoiceNumber.isNotBlank()) score += 14
        if (total.isNotBlank()) score += 22
        if (tax.isNotBlank()) score += 12
        if (type != context.uiText(languageCode, "b2_receipt_detected_financial")) score += 8

        return ParsedData(
            documentType = type,
            vendor = vendor,
            date = date,
            invoiceNumber = invoiceNumber,
            total = total,
            tax = tax,
            confidence = score.coerceAtMost(98),
            rawText = cleanText
        )
    }

    fun structuredText(context: Context, languageCode: String, data: ParsedData): String = buildString {
        appendLine(context.uiText(languageCode, "b2_export_pair", context.uiText(languageCode, "b2_receipt_type"), data.documentType))
        appendLine(context.uiText(languageCode, "b2_export_pair", context.uiText(languageCode, "b2_receipt_vendor"), data.vendor.ifBlank { "-" }))
        appendLine(context.uiText(languageCode, "b2_export_pair", context.uiText(languageCode, "b2_receipt_date"), data.date.ifBlank { "-" }))
        appendLine(context.uiText(languageCode, "b2_export_pair", context.uiText(languageCode, "b2_receipt_number"), data.invoiceNumber.ifBlank { "-" }))
        appendLine(context.uiText(languageCode, "b2_export_pair", context.uiText(languageCode, "b2_receipt_total"), data.total.ifBlank { "-" }))
        appendLine(context.uiText(languageCode, "b2_export_pair", context.uiText(languageCode, "b2_receipt_tax"), data.tax.ifBlank { "-" }))
        appendLine(context.uiText(languageCode, "b2_receipt_conf", data.confidence))
        if (data.rawText.isNotBlank()) {
            appendLine()
            appendLine(context.uiText(languageCode, "b2_receipt_export_original"))
            append(data.rawText)
        }
    }

    fun saveResult(context: Context, languageCode: String, data: ParsedData, baseName: String): File {
        val safe = baseName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Invoice_Receipt" }
        return File(FileUtils.outputDir(context, "receipts"), "${safe}_${System.currentTimeMillis()}.txt").apply {
            writeText(structuredText(context, languageCode, data), Charsets.UTF_8)
        }
    }

    fun cleanup(file: File?) {
        file?.parentFile?.takeIf { it.parentFile?.name == "receipt_scan" }?.let { runCatching { it.deleteRecursively() } }
    }

    private fun findInvoiceNumber(lines: List<String>): String {
        val keyword = Regex("(?i)(invoice\\s*(?:no|number|#)?|inv\\s*(?:no|#)?|receipt\\s*(?:no|number|#)?|رقم\\s*(?:الفاتورة|فاتورة|الإيصال|الايصال)|فاتورة\\s*رقم|إيصال\\s*رقم|ايصال\\s*رقم)")
        val valueAfter = Regex("[:#№\\-]?\\s*([A-Za-z0-9٠-٩۰-۹][A-Za-z0-9٠-٩۰-۹_./\\-]{2,})")
        for (line in lines) {
            val match = keyword.find(line) ?: continue
            val tail = line.substring(match.range.last + 1).trim()
            val value = valueAfter.find(tail)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (value.isNotBlank() && value.length <= 40) return value
        }
        return ""
    }

    private fun findDate(lines: List<String>): String {
        val numeric = Regex("\\b(?:20\\d{2}|19\\d{2})[-/.](?:0?[1-9]|1[0-2])[-/.](?:0?[1-9]|[12]\\d|3[01])\\b|\\b(?:0?[1-9]|[12]\\d|3[01])[-/.](?:0?[1-9]|1[0-2])[-/.](?:\\d{2}|20\\d{2}|19\\d{2})\\b")
        val monthName = Regex("(?i)\\b(?:0?[1-9]|[12]\\d|3[01])\\s+(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\s+(?:20\\d{2}|19\\d{2})\\b")
        val priority = lines.sortedByDescending { line -> if (containsAny(line.lowercase(Locale.ROOT), "date", "تاريخ")) 1 else 0 }
        for (line in priority) {
            val normalized = normalizeDigits(line)
            numeric.find(normalized)?.value?.let { return it }
            monthName.find(normalized)?.value?.let { return it }
        }
        return ""
    }

    private fun findMoneyByKeywords(lines: List<String>, keywords: List<String>, excludedKeywords: List<String>): String {
        val money = Regex("(?<![A-Za-z0-9])(?:\\d{1,3}(?:[, ]\\d{3})+(?:[.]\\d{1,3})?|\\d+(?:[.,]\\d{1,3})?)(?![A-Za-z0-9])")
        for (keyword in keywords) {
            val candidates = lines.filter { line ->
                val lower = line.lowercase(Locale.ROOT)
                lower.contains(keyword) && excludedKeywords.none { lower.contains(it) }
            }
            for (line in candidates) {
                val normalizedAmount = normalizeDigits(line).replace('٫', '.').replace('٬', ',')
                val values = money.findAll(normalizedAmount).map { it.value.trim().replace(" ", "") }.toList()
                if (values.isNotEmpty()) return values.last()
            }
        }
        return ""
    }

    private fun findVendor(lines: List<String>): String {
        val skipTerms = listOf(
            "invoice", "receipt", "tax", "vat", "total", "date", "فاتورة", "إيصال", "ايصال", "ضريبة", "الإجمالي", "اجمالي", "تاريخ", "رقم", "tel", "phone", "www", "http"
        )
        return lines.take(10)
            .filter { it.length in 3..70 }
            .filterNot { line ->
                val lower = line.lowercase(Locale.ROOT)
                skipTerms.any { lower.contains(it) } || line.contains('@') || line.count { it.isDigit() } > line.length / 2
            }
            .maxByOrNull { line ->
                val letters = line.count { it.isLetter() }
                val earlyBonus = (10 - lines.indexOf(line).coerceAtLeast(0)) * 2
                letters + earlyBonus
            }
            ?.trim()
            .orEmpty()
    }

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
