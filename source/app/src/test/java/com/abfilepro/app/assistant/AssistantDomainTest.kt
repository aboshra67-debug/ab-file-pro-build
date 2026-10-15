package com.abfilepro.app.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantDomainTest {
    @Test fun exposesAllSixteenModules() {
        assertEquals(16, AssistantModule.entries.size)
        assertEquals(16, AssistantModule.entries.map { it.storageId }.distinct().size)
    }

    @Test fun categoryFilteringKeepsSharedModulesVisible() {
        val personal = AssistantDomain.modulesFor(AssistantCategory.PERSONAL)
        val work = AssistantDomain.modulesFor(AssistantCategory.WORK)
        assertTrue(personal.contains(AssistantModule.PRAYER))
        assertTrue(work.contains(AssistantModule.PRAYER))
        assertTrue(personal.contains(AssistantModule.HEALTH))
        assertTrue(work.contains(AssistantModule.MEETINGS))
    }

    @Test fun calculatorHonoursPrecedenceAndUnaryMinus() {
        assertEquals(14.0, AssistantDomain.evaluateExpression("2 + 3 * 4"), 0.0001)
        assertEquals(-5.0, AssistantDomain.evaluateExpression("-(2 + 3)"), 0.0001)
        assertEquals(2.5, AssistantDomain.evaluateExpression("10 / 4"), 0.0001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun calculatorRejectsUnsafeCharacters() {
        AssistantDomain.evaluateExpression("Runtime.exec(1)")
    }
}
