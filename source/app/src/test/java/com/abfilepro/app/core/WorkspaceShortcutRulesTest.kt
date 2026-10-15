package com.abfilepro.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceShortcutRulesTest {
    @Test fun shortcutLabelIsTrimmedAndStable() {
        assertEquals("إدارة ملفات AB برو", WorkspaceShortcutRules.shortLabel("  احمد  "))
        assertEquals(WorkspaceShortcutRules.shortcutId("احمد"), WorkspaceShortcutRules.shortcutId(" احمد "))
    }

    @Test fun explorerAppearanceFallsBackSafely() {
        assertEquals("dark", ExplorerAppearanceRules.theme("dark"))
        assertEquals("default", ExplorerAppearanceRules.theme("unknown"))
        assertEquals("cards", ExplorerAppearanceRules.style("cards"))
        assertEquals("windows", ExplorerAppearanceRules.style(null))
    }
}
