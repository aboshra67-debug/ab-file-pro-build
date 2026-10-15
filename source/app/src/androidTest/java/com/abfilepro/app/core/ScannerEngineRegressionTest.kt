package com.abfilepro.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** P22: Google-first scanner choice and one save folder for every scanner mode. */
@RunWith(AndroidJUnit4::class)
class ScannerEngineRegressionTest {
    private lateinit var context: Context
    private val cleanup = mutableListOf<File>()

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ab_file_pro_scanner_v22", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() {
        context.getSharedPreferences("ab_file_pro_scanner_v22", Context.MODE_PRIVATE).edit().clear().commit()
        cleanup.reversed().forEach { it.deleteRecursively() }
    }

    @Test fun googleScannerIsTheDefaultEngineAndTheChoiceIsRemembered() {
        assertEquals(ScannerEngine.GOOGLE, ScannerEngineStore.engine(context))
        ScannerEngineStore.setEngine(context, ScannerEngine.BUILT_IN)
        assertEquals(ScannerEngine.BUILT_IN, ScannerEngineStore.engine(context))
        ScannerEngineStore.setEngine(context, ScannerEngine.GOOGLE)
        assertEquals(ScannerEngine.GOOGLE, ScannerEngineStore.engine(context))
    }

    @Test fun everyScannerModeSavesIntoTheScansFolderByDefault() {
        val expected = FileUtils.outputDir(context, ScannerEngineStore.DEFAULT_FOLDER).canonicalFile
        assertEquals(expected, ScannerEngineStore.saveDirectory(context).canonicalFile)
    }

    @Test fun aFolderPickedInTheScannerIsRememberedAndOutsideFoldersAreRefused() {
        val picked = File(FileUtils.rootDir(context), "P22_PICK_${UUID.randomUUID()}").apply { check(mkdirs()) }
        cleanup += picked
        ScannerEngineStore.setSaveDirectory(context, picked)
        assertEquals(picked.canonicalFile, ScannerEngineStore.saveDirectory(context).canonicalFile)
        try {
            ScannerEngineStore.setSaveDirectory(context, context.cacheDir)
            fail("a folder outside AB File Pro must be refused")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(picked.canonicalFile, ScannerEngineStore.saveDirectory(context).canonicalFile)
    }

    @Test fun aRemovedPickedFolderFallsBackOrIsRecreatedNeverOutsideTheWorkspace() {
        val picked = File(FileUtils.rootDir(context), "P22_GONE_${UUID.randomUUID()}").apply { check(mkdirs()) }
        cleanup += picked
        ScannerEngineStore.setSaveDirectory(context, picked)
        picked.deleteRecursively()
        val resolved = ScannerEngineStore.saveDirectory(context)
        assertTrue(resolved.isDirectory)
        assertTrue(FileUtils.isInsideRoot(context, resolved))
    }

    @Test fun twoSidedCardIsSavedIntoTheScannerFolder() {
        val folder = File(FileUtils.rootDir(context), "P22_CARD_${UUID.randomUUID()}").apply { check(mkdirs()) }
        cleanup += folder
        val work = File(context.cacheDir, "p22_card_${UUID.randomUUID()}").apply { check(mkdirs()) }
        cleanup += work
        fun side(name: String, color: Int) = File(work, name).also { file ->
            val bitmap = Bitmap.createBitmap(856, 540, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            try { FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) } } finally { bitmap.recycle() }
        }
        val pdf = CardScanProcessor.saveCombinedPdf(
            context, side("front.jpg", Color.RED), side("back.jpg", Color.BLUE),
            CardPageLayout.VERTICAL, "P22Card", destinationDir = folder
        )
        assertEquals(folder.canonicalFile, pdf.parentFile!!.canonicalFile)
        assertTrue(pdf.length() > 0)
        assertTrue(String(pdf.readBytes().copyOfRange(0, 5)) == "%PDF-")
    }
}
