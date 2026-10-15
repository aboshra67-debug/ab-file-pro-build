package com.abfilepro.app.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P17: family sharing rules (opt-in, health consent, no leaking across families). */
class FamilySyncPolicyTest {
    @Test fun inviteParsesCodeAndSecret() {
        assertEquals(FamilySyncPolicy.Invite("123456", "ABCD234567"), FamilySyncPolicy.parseInvite("123456-ABCD234567"))
    }

    @Test fun inviteAcceptsSpacesLowerCaseAndArabicDigits() {
        assertEquals(FamilySyncPolicy.Invite("123456", "ABCD234567"), FamilySyncPolicy.parseInvite(" ١٢٣٤٥٦ - abcd 2345 67 "))
        assertEquals(FamilySyncPolicy.Invite("123456", "ABCD234567"), FamilySyncPolicy.parseInvite("۱۲۳۴۵۶ABCD234567"))
    }

    @Test fun codeAloneIsNotAnInvite() {
        assertNull(FamilySyncPolicy.parseInvite("123456"))
        assertNull(FamilySyncPolicy.parseInvite("123456-ABC"))
    }

    @Test fun inviteRejectsAmbiguousSecretCharactersAndNonNumericCode() {
        assertNull(FamilySyncPolicy.parseInvite("123456-ABCD0O1I23"))
        assertNull(FamilySyncPolicy.parseInvite("12A456-ABCD234567"))
    }

    @Test fun healthStaysOnThePhoneUntilSharingIsTurnedOn() {
        assertFalse(FamilySyncPolicy.shouldUpload("h1", isHealth = true, shareHealth = false, excludedIds = emptySet(), changedAt = 10, pushedUpTo = 0))
        assertTrue(FamilySyncPolicy.shouldUpload("h1", isHealth = true, shareHealth = true, excludedIds = emptySet(), changedAt = 10, pushedUpTo = 0))
    }

    @Test fun itemsThatExistedBeforeJoiningAnotherFamilyStayLocal() {
        assertFalse(FamilySyncPolicy.shouldUpload("old", isHealth = false, shareHealth = true, excludedIds = setOf("old"), changedAt = 99, pushedUpTo = 0))
        assertTrue(FamilySyncPolicy.shouldUpload("new", isHealth = false, shareHealth = true, excludedIds = setOf("old"), changedAt = 99, pushedUpTo = 0))
    }

    @Test fun onlyChangesNewerThanTheLastConfirmedUploadAreSent() {
        assertFalse(FamilySyncPolicy.shouldUpload("x", false, false, emptySet(), changedAt = 50, pushedUpTo = 50))
        assertTrue(FamilySyncPolicy.shouldUpload("x", false, false, emptySet(), changedAt = 51, pushedUpTo = 50))
    }

    @Test fun ownHealthIsProtectedFromRemoteChangesWhileSharingIsOff() {
        assertFalse(FamilySyncPolicy.acceptRemoteForLocalHealth(localIsOwnHealth = true, shareHealth = false))
        assertTrue(FamilySyncPolicy.acceptRemoteForLocalHealth(localIsOwnHealth = true, shareHealth = true))
        assertTrue(FamilySyncPolicy.acceptRemoteForLocalHealth(localIsOwnHealth = false, shareHealth = false))
    }

    @Test fun uploadsAreChunkedSoNothingIsDroppedByTheServerLimit() {
        val chunks = FamilySyncPolicy.chunks((1..1001).toList(), 400)
        assertEquals(listOf(400, 400, 201), chunks.map { it.size })
        assertEquals((1..1001).toList(), chunks.flatten())
        assertTrue(FamilySyncPolicy.chunks(emptyList<Int>()).isEmpty())
    }
}
