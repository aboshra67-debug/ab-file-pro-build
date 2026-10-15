package com.abfilepro.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ExplorerPremiumRulesTest {
    @Test fun renameWithoutNewExtension_keepsOriginalFileExtension() {
        assertEquals("holiday.jpg", ExplorerPremiumRules.renameKeepingExtension("photo.jpg", "holiday", false))
    }

    @Test fun renameWithDifferentExtension_stillPreservesOriginalFileExtension() {
        assertEquals("holiday.jpg", ExplorerPremiumRules.renameKeepingExtension("photo.jpg", "holiday.png", false))
    }

    @Test fun directoryRename_doesNotAppendExtension() {
        assertEquals("Work", ExplorerPremiumRules.renameKeepingExtension("Docs", "Work", true))
    }

    @Test fun textFileName_addsTxtOnlyWhenMissing() {
        assertEquals("notes.txt", ExplorerPremiumRules.textFileName("notes"))
        assertEquals("notes.TXT", ExplorerPremiumRules.textFileName("notes.TXT"))
    }

    @Test fun visualKind_classifiesPremiumCardTypes() {
        assertEquals(ExplorerVisualKind.PDF, ExplorerPremiumRules.visualKind("report.pdf", false))
        assertEquals(ExplorerVisualKind.IMAGE, ExplorerPremiumRules.visualKind("holiday.jpg", false))
        assertEquals(ExplorerVisualKind.FOLDER, ExplorerPremiumRules.visualKind("Docs", true))
    }
}
