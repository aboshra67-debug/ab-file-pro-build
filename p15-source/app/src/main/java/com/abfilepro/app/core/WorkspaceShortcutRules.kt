package com.abfilepro.app.core

object WorkspaceShortcutRules {
    const val EXTRA_OPEN_WORKSPACE = "com.abfilepro.app.OPEN_WORKSPACE"
    const val FILE_MANAGER_LABEL = "إدارة ملفات AB برو"

    fun shortLabel(rawName: String): String = FILE_MANAGER_LABEL

    fun shortcutId(rawName: String): String {
        val normalized = rawName.trim().ifBlank { "AB File Pro" }.lowercase()
        return "workspace_${normalized.hashCode().toUInt().toString(16)}"
    }
}

object ExplorerAppearanceRules {
    private val themes = setOf("default", "light", "dark", "blue")
    private val styles = setOf("windows", "cards", "compact")

    fun theme(raw: String?): String = raw?.lowercase()?.takeIf(themes::contains) ?: "default"
    fun style(raw: String?): String = raw?.lowercase()?.takeIf(styles::contains) ?: "windows"
}
