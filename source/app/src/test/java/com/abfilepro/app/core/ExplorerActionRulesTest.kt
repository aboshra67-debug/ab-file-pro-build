package com.abfilepro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorerActionRulesTest {
    @Test fun regularFile_longPressActions_includeDesktopBasics() {
        val actions = ExplorerActionRules.actionsFor(
            isDirectory = false,
            extension = "pdf",
            isImage = false,
            clipboardAvailable = true
        )
        assertTrue(ExplorerAction.OPEN in actions)
        assertTrue(ExplorerAction.COPY in actions)
        assertTrue(ExplorerAction.CUT in actions)
        assertTrue(ExplorerAction.PASTE in actions)
        assertTrue(ExplorerAction.RENAME in actions)
        assertTrue(ExplorerAction.DUPLICATE in actions)
        assertTrue(ExplorerAction.OPEN_WITH in actions)
        assertTrue(ExplorerAction.FAVORITE in actions)
        assertTrue(ExplorerAction.SHARE in actions)
        assertTrue(ExplorerAction.ZIP in actions)
        assertTrue(ExplorerAction.PROPERTIES in actions)
        assertTrue(ExplorerAction.DELETE in actions)
    }

    @Test fun image_longPressActions_offerImagesToPdf() {
        val actions = ExplorerActionRules.actionsFor(false, "jpg", true, false)
        assertTrue(ExplorerAction.IMAGES_TO_PDF in actions)
    }

    @Test fun zip_longPressActions_offerBothExtractModes() {
        val actions = ExplorerActionRules.actionsFor(false, "zip", false, false)
        assertTrue(ExplorerAction.EXTRACT_HERE in actions)
        assertTrue(ExplorerAction.EXTRACT_FOLDER in actions)
    }

    @Test fun directory_doesNotOfferShareOrImagesToPdf() {
        val actions = ExplorerActionRules.actionsFor(true, "", false, false)
        assertFalse(ExplorerAction.SHARE in actions)
        assertFalse(ExplorerAction.IMAGES_TO_PDF in actions)
    }

    @Test fun noClipboard_hidesPaste() {
        val actions = ExplorerActionRules.actionsFor(false, "txt", false, false)
        assertFalse(ExplorerAction.PASTE in actions)
    }
}
