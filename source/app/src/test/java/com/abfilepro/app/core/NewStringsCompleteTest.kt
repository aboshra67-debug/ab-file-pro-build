package com.abfilepro.app.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** P34: the strings added in P32-P34 exist in Arabic, English and French (uiText falls back to English otherwise). */
class NewStringsCompleteTest {
    private val files = listOf("strings_p32_scanner.xml", "strings_p33_points.xml", "strings_p34_settings.xml")

    private fun names(file: String): Set<String> {
        val xml = File("src/main/res/values/$file").readText()
        return Regex("""<string name="([a-z0-9_]+)"""").findAll(xml).map { it.groupValues[1] }.toSet()
    }

    @Test fun everyKeyHasThreeLanguages() {
        files.forEach { file ->
            val all = names(file)
            assertTrue("$file has no strings", all.isNotEmpty())
            val bases = all.filter { it.endsWith("_ar") }.map { it.removeSuffix("_ar") }
            bases.forEach { base ->
                assertTrue("$file: ${base}_en missing", "${base}_en" in all)
                assertTrue("$file: ${base}_fr missing", "${base}_fr" in all)
            }
            all.forEach { n ->
                val base = n.substringBeforeLast('_')
                assertTrue("$file: $n has no Arabic twin", "${base}_ar" in all)
            }
        }
    }
}
