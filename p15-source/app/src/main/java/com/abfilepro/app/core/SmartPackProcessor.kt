package com.abfilepro.app.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.abfilepro.app.ui.i18n.uiText
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

object SmartPackProcessor {
    data class PlanItem(
        val uri: Uri,
        val originalName: String,
        val category: String,
        val cluster: String,
        val proposedBaseName: String,
        val duplicateOf: String?,
        val similarTo: String?,
        val textPreview: String
    )

    data class Group(val category: String, val cluster: String, val count: Int)

    data class Plan(
        val items: List<PlanItem>,
        val groups: List<Group>,
        val duplicates: Int,
        val similarFiles: Int
    )

    data class Result(
        val root: File,
        val report: File,
        val groups: List<Group>,
        val filesCopied: Int,
        val duplicates: Int,
        val historyId: String
    )

    private data class Analyzed(
        val uri: Uri,
        val originalName: String,
        val categoryKey: String,
        val tokens: Set<String>,
        val textPreview: String,
        val hash: String
    )

    suspend fun analyze(context: Context, uris: List<Uri>, languageCode: String = "ar", onProgress: suspend (String) -> Unit = {}): Plan = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty()) { context.uiText(languageCode, "b2_pack_error_pick_first") }
        require(uris.size <= 50) { context.uiText(languageCode, "b2_pack_error_limit", 50) }

        val analyzed = buildList {
            uris.forEachIndexed { index, uri ->
                currentCoroutineContext().ensureActive()
                onProgress(context.uiText(languageCode, "b2_pack_progress_analyze", index + 1, uris.size))
                val name = displayName(context, uri, index)
                val text = extractText(context, uri).take(5000)
                add(Analyzed(
                    uri = uri,
                    originalName = name,
                    categoryKey = classifyKey("$name $text"),
                    tokens = tokenize("$name $text"),
                    textPreview = text.take(240).replace('\n', ' '),
                    hash = sha256(context, uri)
                ))
            }
        }

        val tokenFreq = analyzed.flatMap { it.tokens }.groupingBy { it }.eachCount()
        val firstByHash = mutableMapOf<String, String>()
        val groupCounts = linkedMapOf<Pair<String, String>, Int>()
        val items = analyzed.mapIndexed { index, item ->
            val cluster = item.tokens
                .filter { (tokenFreq[it] ?: 0) >= 2 }
                .maxByOrNull { tokenFreq[it] ?: 0 }
                ?: context.uiText(languageCode, "b2_pack_cluster_general")
            val duplicateOf = firstByHash[item.hash]
            if (duplicateOf == null) firstByHash[item.hash] = item.originalName

            val similarTo = if (duplicateOf == null) {
                analyzed.take(index)
                    .asSequence()
                    .map { previous -> previous.originalName to jaccard(item.tokens, previous.tokens) }
                    .filter { it.second >= .62 }
                    .maxByOrNull { it.second }
                    ?.first
            } else null

            val localizedCategory = context.uiText(languageCode, item.categoryKey)
            val folderCategory = if (duplicateOf != null) context.uiText(languageCode, "b2_pack_category_duplicates") else localizedCategory
            groupCounts[folderCategory to cluster] = (groupCounts[folderCategory to cluster] ?: 0) + 1
            PlanItem(
                uri = item.uri,
                originalName = item.originalName,
                category = folderCategory,
                cluster = cluster,
                proposedBaseName = safeFolder("${localizedCategory}_${cluster}_${(index + 1).toString().padStart(2, '0')}").take(70),
                duplicateOf = duplicateOf,
                similarTo = similarTo,
                textPreview = item.textPreview
            )
        }

        Plan(
            items = items,
            groups = groupCounts.map { Group(it.key.first, it.key.second, it.value) },
            duplicates = items.count { it.duplicateOf != null },
            similarFiles = items.count { it.similarTo != null }
        )
    }

    suspend fun apply(context: Context, plan: Plan, languageCode: String = "ar", onProgress: suspend (String) -> Unit = {}): Result = withContext(Dispatchers.IO) {
        RuntimeSafety.ensureStorage(context)
        require(plan.items.isNotEmpty()) { context.uiText(languageCode, "b2_pack_error_no_plan") }
        val stamp = System.currentTimeMillis()
        val root = FileUtils.outputDir(context, "AB Smart Pack/Pack_$stamp")
        val mappings = mutableListOf<String>()

        try {
        plan.items.forEachIndexed { index, item ->
            currentCoroutineContext().ensureActive()
            onProgress(context.uiText(languageCode, "b2_pack_progress_copy", index + 1, plan.items.size))
            val categoryDir = File(root, safeFolder(item.category)).apply { mkdirs() }
            val clusterDir = File(categoryDir, safeFolder(item.cluster)).apply { mkdirs() }
            val copied = FileUtils.importUriCancellable(context, item.uri, clusterDir)
            val renamed = runCatching { FileUtils.renameFile(copied, item.proposedBaseName) }.getOrDefault(copied)
            val note = when {
                item.duplicateOf != null -> context.uiText(languageCode, "b2_pack_report_duplicate_note", item.duplicateOf)
                item.similarTo != null -> context.uiText(languageCode, "b2_pack_report_similar_note", item.similarTo)
                else -> ""
            }
            mappings += "${item.originalName} -> ${item.category}/${item.cluster}/${renamed.name}$note"
        }

        val report = File(root, "AB_Smart_Pack_Report.txt")
        report.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine("AB Smart Pack")
            writer.appendLine(context.uiText(languageCode, "b2_pack_report_files", plan.items.size))
            writer.appendLine(context.uiText(languageCode, "b2_pack_report_duplicates", plan.duplicates))
            writer.appendLine(context.uiText(languageCode, "b2_pack_report_similar", plan.similarFiles))
            writer.appendLine(context.uiText(languageCode, "b2_pack_report_safety"))
            writer.appendLine()
            plan.groups.forEach { writer.appendLine("${it.category} / ${it.cluster}: ${it.count}") }
            writer.appendLine()
            mappings.forEach(writer::appendLine)
        }
        val history = OperationHistoryStore.record(context, "AB Smart Pack", context.uiText(languageCode, "b2_pack_history", plan.items.size), listOf(root))
        Result(root, report, plan.groups, plan.items.size, plan.duplicates, history.id)
        } catch (error: Throwable) {
            root.deleteRecursively()
            throw error
        }
    }

    suspend fun pack(context: Context, uris: List<Uri>, languageCode: String = "ar", onProgress: suspend (String) -> Unit = {}): Result {
        val plan = analyze(context, uris, languageCode, onProgress)
        return apply(context, plan, languageCode, onProgress)
    }

    private suspend fun extractText(context: Context, uri: Uri): String {
        val type = context.contentResolver.getType(uri).orEmpty().lowercase()
        return when {
            type == "application/pdf" -> runCatching {
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input)
                    PDDocument.load(input).use { doc ->
                        PDFTextStripper().apply { startPage = 1; endPage = minOf(3, doc.numberOfPages) }.getText(doc)
                    }
                }
            }.getOrDefault("")
            type.startsWith("image/") || type.isBlank() -> {
                try {
                    if (OcrProcessor.isModelReady(context, OcrProcessor.Language.ALL)) {
                        OcrProcessor.recognize(context, uri, OcrProcessor.Language.ALL)
                    } else if (OcrProcessor.isModelReady(context, OcrProcessor.Language.ARABIC_ENGLISH)) {
                        OcrProcessor.recognize(context, uri, OcrProcessor.Language.ARABIC_ENGLISH)
                    } else {
                        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                        try { recognizer.process(InputImage.fromFilePath(context, uri)).await().text } finally { recognizer.close() }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    ""
                }
            }
            type.startsWith("text/") -> runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText().take(10000) }.orEmpty()
            }.getOrDefault("")
            else -> ""
        }
    }

    private fun classifyKey(raw: String): String {
        val t = normalize(raw)
        val rules = listOf(
            "b2_pack_category_invoices" to listOf("فاتورة", "invoice", "facture", "receipt", "recu", "ايصال", "purchase", "توريد", "مورد"),
            "b2_pack_category_contracts" to listOf("عقد", "contract", "contrat", "agreement", "accord", "اتفاق", "طرف اول", "طرف ثاني"),
            "b2_pack_category_identity" to listOf("بطاقة", "هوية", "passport", "passeport", "جواز", "national id", "رقم قومي"),
            "b2_pack_category_payments" to listOf("bank", "banque", "بنك", "iban", "تحويل", "payment", "paiement", "حساب", "account"),
            "b2_pack_category_education" to listOf("جامعة", "مدرسة", "student", "etudiant", "طالب", "course", "cours", "محاضرة", "امتحان"),
            "b2_pack_category_reports" to listOf("report", "rapport", "تقرير", "محضر", "memo", "خطاب", "letter", "courrier")
        )
        return rules.maxByOrNull { (_, words) -> words.count { t.contains(normalize(it)) } }
            ?.takeIf { (_, words) -> words.any { t.contains(normalize(it)) } }?.first ?: "b2_pack_category_other"
    }

    private fun tokenize(raw: String): Set<String> {
        val stop = setOf("the", "and", "for", "from", "this", "that", "file", "page", "pdf", "image", "scan", "with", "les", "des", "une", "dans", "من", "في", "على", "الى", "إلى", "هذا", "هذه", "ملف", "صفحة", "صورة", "رقم")
        return normalize(raw).split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && it !in stop && !it.all(Char::isDigit) }
            .take(100).toSet()
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.intersect(b).size.toDouble()
        val union = a.union(b).size.toDouble().coerceAtLeast(1.0)
        return intersection / union
    }

    private suspend fun sha256(context: Context, uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "تعذر قراءة الملف" }
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun normalize(s: String): String = s.lowercase()
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه')
        .replace('é', 'e').replace('è', 'e').replace('ê', 'e').replace('à', 'a').replace('ç', 'c')
        .replace(Regex("\\s+"), " ")

    private fun safeFolder(s: String): String = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(70).ifBlank { "General" }

    private fun displayName(context: Context, uri: Uri, index: Int): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val i = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && cursor.moveToFirst()) cursor.getString(i) else null
        } ?: "file_${index + 1}"
}
