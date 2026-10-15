package com.abfilepro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileManagerRulesTest {
    @Test fun safeName_removesProviderUnsafeCharacters() {
        assertEquals("report_2026_.pdf", FileManagerRules.safeName(" report:2026?.pdf "))
        assertEquals("", FileManagerRules.safeName(".."))
    }

    @Test fun uniqueName_preservesExtensionAndSkipsExistingSuffixes() {
        val existing = setOf("report.pdf", "report (2).pdf", "notes.txt")
        assertEquals("report (3).pdf", FileManagerRules.uniqueName("report.pdf", existing))
        assertEquals("folder (2)", FileManagerRules.uniqueName("folder", setOf("folder")))
    }

    @Test fun zipPathSafety_rejectsTraversalAndAbsolutePaths() {
        assertTrue(FileManagerRules.isSafeZipPath("folder/document.txt"))
        assertFalse(FileManagerRules.isSafeZipPath("../secret.txt"))
        assertFalse(FileManagerRules.isSafeZipPath("folder/../../secret.txt"))
        assertFalse(FileManagerRules.isSafeZipPath("/absolute.txt"))
        assertFalse(FileManagerRules.isSafeZipPath("C:\\absolute.txt"))
    }

    @Test fun previewSampleSize_boundsHugeImagesWithPowerOfTwoSampling() {
        assertEquals(1, FileManagerRules.previewSampleSize(1200, 800, 1280))
        assertEquals(2, FileManagerRules.previewSampleSize(2400, 1600, 1280))
        assertEquals(8, FileManagerRules.previewSampleSize(8000, 6000, 1280))
        assertEquals(1, FileManagerRules.previewSampleSize(0, 0, 1280))
    }

    @Test fun previewKind_usesMimeThenSafeFileExtensions() {
        assertEquals(FileManagerRules.PreviewKind.IMAGE, FileManagerRules.previewKind("photo.bin", "image/jpeg"))
        assertEquals(FileManagerRules.PreviewKind.IMAGE, FileManagerRules.previewKind("photo.PNG", "application/octet-stream"))
        assertEquals(FileManagerRules.PreviewKind.TEXT, FileManagerRules.previewKind("notes.txt", "application/octet-stream"))
        assertEquals(FileManagerRules.PreviewKind.TEXT, FileManagerRules.previewKind("data.json", "application/json"))
        assertEquals(FileManagerRules.PreviewKind.NONE, FileManagerRules.previewKind("archive.zip", "application/zip"))
    }

    @Test fun quickFolderKind_recognizesAndroidStandardFolders() {
        assertEquals(FileManagerRules.QuickFolder.DOWNLOADS, FileManagerRules.quickFolderKind("Download"))
        assertEquals(FileManagerRules.QuickFolder.PICTURES, FileManagerRules.quickFolderKind("Pictures"))
        assertEquals(FileManagerRules.QuickFolder.VIDEOS, FileManagerRules.quickFolderKind("Movies"))
        assertEquals(FileManagerRules.QuickFolder.DOCUMENTS, FileManagerRules.quickFolderKind("Documents"))
        assertEquals(FileManagerRules.QuickFolder.DCIM, FileManagerRules.quickFolderKind("DCIM"))
        assertEquals(null, FileManagerRules.quickFolderKind("RandomFolder"))
    }

    @Test fun sortEntries_keepsFoldersFirstAndSortsByRequestedKey() {
        val input = listOf(
            FileManagerRules.EntryMeta("z.pdf", false, 300, 10, "application/pdf"),
            FileManagerRules.EntryMeta("Folder B", true, 0, 5, "vnd.android.document/directory"),
            FileManagerRules.EntryMeta("a.txt", false, 100, 30, "text/plain"),
            FileManagerRules.EntryMeta("Folder A", true, 0, 20, "vnd.android.document/directory")
        )
        assertEquals(
            listOf("Folder A", "Folder B", "a.txt", "z.pdf"),
            FileManagerRules.sortEntries(input, FileManagerRules.SortKey.NAME, true).map { it.name }
        )
        assertEquals(
            listOf("Folder A", "Folder B", "z.pdf", "a.txt"),
            FileManagerRules.sortEntries(input, FileManagerRules.SortKey.SIZE, false).map { it.name }
        )
    }
}
