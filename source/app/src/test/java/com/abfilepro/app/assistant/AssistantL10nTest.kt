package com.abfilepro.app.assistant

import com.abfilepro.app.ui.i18n.AppLanguage
import com.abfilepro.app.ui.screens.assistant.AssistantLang
import com.abfilepro.app.ui.screens.assistant.L
import com.abfilepro.app.ui.screens.assistant.L10N_TABLE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantL10nTest {
    private val arabicLetters = Regex("[\\u0600-\\u06FF]")

    @After
    fun reset() {
        AssistantLang.language = AppLanguage.ARABIC
    }

    @Test
    fun arabicIsNeverChanged() {
        AssistantLang.language = AppLanguage.ARABIC
        L10N_TABLE.keys.forEach { assertEquals(it, L(it)) }
        assertEquals("فاتورة الكهرباء", L("فاتورة الكهرباء"))
    }

    @Test
    fun everyEntryHasRealEnglishAndFrenchWithoutArabicLeftovers() {
        L10N_TABLE.forEach { (ar, pair) ->
            val (en, fr) = pair
            assertTrue("English is blank for $ar", en.isNotBlank())
            assertTrue("French is blank for $ar", fr.isNotBlank())
            assertFalse("Arabic left in English for $ar", arabicLetters.containsMatchIn(en))
            assertFalse("Arabic left in French for $ar", arabicLetters.containsMatchIn(fr))
        }
    }

    @Test
    fun templatesAreTranslatedWithTheirValues() {
        AssistantLang.language = AppLanguage.ENGLISH
        assertEquals("Electricity bill", L("فاتورة ${L("الكهرباء")}"))
        assertEquals("Overdue by 3 days", L("متأخرة منذ 3 يوم"))
        assertEquals("Due in 4 days", L("تستحق خلال 4 أيام"))
        assertEquals("25%", L("25٪"))
        assertEquals("100 EGP", L("100 ج.م"))
        AssistantLang.language = AppLanguage.FRENCH
        assertEquals("Facture Gaz", L("فاتورة ${L("الغاز")}"))
        assertEquals("À payer dans 2 jours", L("تستحق خلال 2 أيام"))
    }
}
