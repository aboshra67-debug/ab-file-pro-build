package com.abfilepro.app.core

/** Pure deterministic rules shared by private-file and SAF file-manager code. */
object FileManagerRules {
    enum class SortKey { DATE, NAME, SIZE, TYPE }
    enum class PreviewKind { NONE, IMAGE, TEXT }
    enum class QuickFolder { DOWNLOADS, PICTURES, VIDEOS, DOCUMENTS, MUSIC, DCIM }

    data class EntryMeta(
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
        val mimeType: String,
        val id: String = name
    )

    fun safeName(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim()
        return if (cleaned == "." || cleaned == "..") "" else cleaned
    }

    fun uniqueName(requestedName: String, existingNames: Collection<String>): String {
        val safe = safeName(requestedName).ifBlank { "file" }
        val existing = existingNames.mapTo(HashSet()) { it.lowercase() }
        if (safe.lowercase() !in existing) return safe

        val dot = safe.lastIndexOf('.')
        val hasExtension = dot > 0 && dot < safe.lastIndex
        val base = if (hasExtension) safe.substring(0, dot) else safe
        val ext = if (hasExtension) safe.substring(dot) else ""
        var index = 2
        while (true) {
            val candidate = "$base ($index)$ext"
            if (candidate.lowercase() !in existing) return candidate
            index++
        }
    }

    fun extensionOf(name: String): String = name.substringAfterLast('.', "")
        .takeIf { it.isNotBlank() && it != name }
        ?.lowercase()
        .orEmpty()

    fun quickFolderKind(name: String): QuickFolder? = when (name.trim().lowercase()) {
        "download", "downloads" -> QuickFolder.DOWNLOADS
        "pictures", "picture", "images", "image" -> QuickFolder.PICTURES
        "movies", "movie", "videos", "video" -> QuickFolder.VIDEOS
        "documents", "document", "docs", "doc" -> QuickFolder.DOCUMENTS
        "music", "audio" -> QuickFolder.MUSIC
        "dcim", "camera" -> QuickFolder.DCIM
        else -> null
    }

    fun previewSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
        var sample = 1
        while (width / sample > maxDimension || height / sample > maxDimension) {
            sample *= 2
        }
        return sample
    }

    fun previewKind(name: String, mimeType: String): PreviewKind {
        val normalizedMime = mimeType.lowercase()
        if (normalizedMime.startsWith("image/")) return PreviewKind.IMAGE
        if (normalizedMime.startsWith("text/") || normalizedMime in setOf("application/json", "application/xml", "text/xml")) return PreviewKind.TEXT
        return when (extensionOf(name)) {
            "png", "jpg", "jpeg", "webp", "bmp", "gif" -> PreviewKind.IMAGE
            "txt", "md", "log", "json", "xml", "csv" -> PreviewKind.TEXT
            else -> PreviewKind.NONE
        }
    }

    fun isSafeZipPath(rawPath: String): Boolean {
        if (rawPath.isBlank() || '\u0000' in rawPath) return false
        if (Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(rawPath)) return false
        val normalized = rawPath.replace('\\', '/')
        if (normalized.startsWith('/')) return false
        val parts = normalized.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        return parts.none { it == ".." }
    }

    fun sortEntries(entries: List<EntryMeta>, key: SortKey, ascending: Boolean): List<EntryMeta> {
        val keyComparator = when (key) {
            SortKey.NAME -> compareBy<EntryMeta> { it.name.lowercase() }
            SortKey.DATE -> compareBy<EntryMeta> { it.lastModified }
            SortKey.SIZE -> compareBy<EntryMeta> { it.size }
            SortKey.TYPE -> compareBy<EntryMeta> { extensionOf(it.name) }.thenBy { it.name.lowercase() }
        }
        val directional = if (ascending) keyComparator else keyComparator.reversed()
        return entries.sortedWith(
            compareBy<EntryMeta> { !it.isDirectory }
                .then(directional)
                .thenBy { it.name.lowercase() }
        )
    }
}
