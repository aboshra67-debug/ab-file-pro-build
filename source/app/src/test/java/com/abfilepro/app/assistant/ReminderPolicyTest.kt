package com.abfilepro.app.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** P18 (AS-05): a reminder whose lead time already passed fires once instead of never. */
class ReminderPolicyTest {
    private val now = 1_000_000L
    private val minute = 60_000L

    @Test fun normalLeadTimeIsKept() {
        assertEquals(now + 45 * minute, ReminderPolicy.triggerFor(now + 60 * minute, 15 * minute, now, null))
    }

    @Test fun passedLeadTimeFiresRightAwayWhileTheEventIsStillAhead() {
        assertEquals(now + ReminderPolicy.IMMEDIATE_DELAY_MS, ReminderPolicy.triggerFor(now + 5 * minute, 15 * minute, now, null))
    }

    @Test fun passedLeadTimeIsNotRepeatedAfterItWasShown() {
        val event = now + 5 * minute
        assertNull(ReminderPolicy.triggerFor(event, 15 * minute, now, alreadyNotifiedFor = event))
    }

    @Test fun movedEventIsRemindedAgain() {
        assertEquals(now + ReminderPolicy.IMMEDIATE_DELAY_MS, ReminderPolicy.triggerFor(now + 6 * minute, 15 * minute, now, alreadyNotifiedFor = now + 5 * minute))
    }

    @Test fun pastEventsAreNotScheduled() {
        assertNull(ReminderPolicy.triggerFor(now - 1, 0, now, null))
        assertNull(ReminderPolicy.triggerFor(now, 0, now, null))
    }
}
