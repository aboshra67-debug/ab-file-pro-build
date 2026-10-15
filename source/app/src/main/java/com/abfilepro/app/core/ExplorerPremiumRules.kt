package com.abfilepro.app.core

enum class ExplorerVisualKind { FOLDER, IMAGE, PDF, ZIP, VIDEO, AUDIO, TEXT, OTHER }

object ExplorerPremiumRules {
    fun renameKeepingExtension(originalName: String, requestedName: String, isDirectory: Boolean): String {
        val safe = FileManagerRules.safeName(requestedName)
        if (isDirectory) return safe
        if (safe.isBlank()) return safe
        val originalExt = FileManagerRules.extensionOf(originalName)
        if (originalExt.isBlank()) return safe
        val requestedExt = FileManagerRules.extensionOf(safe)
        val base = if (requestedExt.isNotBlank()) safe.substringBeforeLast('.').ifBlank { "file" } else safe
        return "$base.$originalExt"
    }

    fun textFileName(requestedName: String): String {
        val safe = FileManagerRules.safeName(requestedName).ifBlank { "New file" }
        return if (safe.endsWith(".txt", ignoreCase = true)) safe else "$safe.txt"
    }

    fun visualKind(name: String, isDirectory: Boolean): ExplorerVisualKind {
        if (isDirectory) return ExplorerVisualKind.FOLDER
        return when (FileManagerRules.extensionOf(name)) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif" -> ExplorerVisualKind.IMAGE
            "pdf" -> ExplorerVisualKind.PDF
            "zip", "rar", "7z", "tar", "gz" -> ExplorerVisualKind.ZIP
            "mp4", "mkv", "avi", "mov", "webm" -> ExplorerVisualKind.VIDEO
            "mp3", "wav", "m4a", "aac", "flac", "ogg" -> ExplorerVisualKind.AUDIO
            "txt", "md", "log", "json", "xml", "csv" -> ExplorerVisualKind.TEXT
            else -> ExplorerVisualKind.OTHER
        }
    }
}
