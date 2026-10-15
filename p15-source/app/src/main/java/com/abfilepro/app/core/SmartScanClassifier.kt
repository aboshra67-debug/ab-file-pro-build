package com.abfilepro.app.core

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max

object SmartScanClassifier {
    enum class Kind {
        DOCUMENT, CARD, INVOICE, RECEIPT, TABLE, BUSINESS_CARD
    }

    data class Result(
        val kind: Kind,
        val confidence: Int,
        val reasonKey: String,
        val suggestedRoute: String,
        val suggestedActionKey: String,
        val extractedPreview: String
    )

    suspend fun analyze(context: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        val bitmap = OcrProcessor.loadPreview(context, uri)
        try {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val visionText = try {
                Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
            } finally {
                recognizer.close()
            }
            val raw = visionText.text.orEmpty().trim()
            val text = raw.lowercase(Locale.ROOT)
            val width = bitmap.width.coerceAtLeast(1)
            val height = bitmap.height.coerceAtLeast(1)
            val landscapeRatio = max(width, height).toFloat() / minOf(width, height).coerceAtLeast(1).toFloat()
            val portraitRatio = height.toFloat() / width.toFloat()
            val lines = visionText.textBlocks.flatMap { it.lines }
            val lineCount = lines.size
            val digitCount = raw.count { it.isDigit() }
            val digitRatio = digitCount.toFloat() / raw.length.coerceAtLeast(1)

            fun hasAny(vararg terms: String) = terms.any { text.contains(it) }
            fun scoreTerms(vararg terms: String) = terms.count { text.contains(it) }

            val businessScore = scoreTerms(
                "email", "www.", "http", "linkedin", "mobile", "phone", "tel", "manager", "director",
                "sales", "company", "شركة", "هاتف", "موبايل", "مدير", "مبيعات"
            ) + if (raw.contains('@')) 2 else 0

            val invoiceScore = scoreTerms(
                "invoice", "tax invoice", "invoice no", "vat", "subtotal", "total", "tax", "amount due",
                "فاتورة", "ضريبة", "الإجمالي", "اجمالي", "المجموع", "رقم الفاتورة"
            )

            val receiptScore = scoreTerms(
                "receipt", "cash", "change", "paid", "payment", "thank you", "store", "merchant",
                "إيصال", "مدفوع", "نقدي", "الباقي", "شكرا"
            ) + if (portraitRatio >= 1.65f && digitRatio > .16f) 2 else 0

            val tableLikeLines = lines.count { line ->
                val value = line.text.trim()
                val tokenCount = value.split(Regex("\\s+")).count { it.isNotBlank() }
                tokenCount >= 4 && value.count { it.isDigit() } >= 2
            }
            val tableScore = if (lineCount >= 6) tableLikeLines else 0

            val cardGeometry = landscapeRatio in 1.45f..1.85f
            val lowText = raw.length < 220 && lineCount <= 12

            val result = when {
                businessScore >= 4 && cardGeometry -> Result(
                    Kind.BUSINESS_CARD,
                    (72 + businessScore * 3).coerceAtMost(96),
                    "b2_smartscan_reason_business",
                    "businesscard",
                    "b2_smartscan_action_business",
                    raw.take(260)
                )
                invoiceScore >= 3 -> Result(
                    Kind.INVOICE,
                    (73 + invoiceScore * 3).coerceAtMost(97),
                    "b2_smartscan_reason_invoice",
                    "receipt",
                    "b2_smartscan_action_invoice",
                    raw.take(260)
                )
                receiptScore >= 3 -> Result(
                    Kind.RECEIPT,
                    (70 + receiptScore * 4).coerceAtMost(96),
                    "b2_smartscan_reason_receipt",
                    "receipt",
                    "b2_smartscan_action_receipt",
                    raw.take(260)
                )
                tableScore >= 3 -> Result(
                    Kind.TABLE,
                    (68 + tableScore * 4).coerceAtMost(94),
                    "b2_smartscan_reason_table",
                    "convert",
                    "b2_smartscan_action_table",
                    raw.take(260)
                )
                cardGeometry && lowText -> Result(
                    Kind.CARD,
                    72,
                    "b2_smartscan_reason_card",
                    "cardscan",
                    "b2_smartscan_action_card",
                    raw.take(260)
                )
                else -> Result(
                    Kind.DOCUMENT,
                    if (raw.isNotBlank()) 82 else 66,
                    if (raw.isNotBlank()) "b2_smartscan_reason_document_text" else "b2_smartscan_reason_document_empty",
                    "scan",
                    "b2_smartscan_action_document",
                    raw.take(260)
                )
            }
            result
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}
