package com.abfilepro.app.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantSearchTest {
    private fun e(id: String, title: String, details: String = "", module: AssistantModule = AssistantModule.NOTES, created: Long = 0L, pinned: Boolean = false) =
        AssistantEntry(id, module, title, details, createdAt = created, pinned = pinned)

    private val all = listOf(
        e("1", "فاتورة الكهرباء", "شهر أكتوبر"),
        e("2", "ملاحظة", "اتصل بأحمد بخصوص الكهرباء", created = 5),
        e("3", "أدوية الضغط", module = AssistantModule.HEALTH),
        e("4", "Meeting with Sara", created = 9),
    )

    @Test fun spellingVariantsMatch() {
        assertEquals(listOf("3"), AssistantSearch.search(all, "ادويه الضغط").map { it.id })
        assertEquals(listOf("4"), AssistantSearch.search(all, "meeting sara").map { it.id })
    }

    @Test fun titleHitsComeBeforeDetailHits() {
        assertEquals(listOf("1", "2"), AssistantSearch.search(all, "الكهرباء").map { it.id })
    }

    @Test fun everyWordMustMatch() {
        assertTrue(AssistantSearch.search(all, "الكهرباء سارة").isEmpty())
    }

    @Test fun blankAndLimit() {
        assertTrue(AssistantSearch.search(all, "  ").isEmpty())
        assertEquals(1, AssistantSearch.search(all, "الكهرباء", limit = 1).size)
    }
}
