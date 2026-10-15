package com.abfilepro.app.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P17 (FAM-N02): opening the assistant must never create, register or sync a family by itself, and
 * a pre-P17 automatic link stays paused until the user decides. No network is needed: every case
 * proves that no session exists, so no request can be made.
 */
@RunWith(AndroidJUnit4::class)
class FamilyOptInRegressionTest {
    private lateinit var context: Context
    private val prefNames = listOf("ab_file_pro_family_link", "ab_file_pro_family_cloud_sync", "ab_personal_assistant")
    private val token = "t".repeat(43)

    @Before fun clean() {
        context = ApplicationProvider.getApplicationContext()
        prefNames.forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        java.io.File(context.filesDir, "assistant").deleteRecursively(); AssistantStore.resetCacheForTests()
    }

    @After fun cleanup() {
        prefNames.forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        java.io.File(context.filesDir, "assistant").deleteRecursively(); AssistantStore.resetCacheForTests()
    }

    private fun manager() = FamilyCloudSyncManager(context, AssistantRepository(context))

    @Test fun freshInstallHasNoFamilyAndNoSession() {
        val profile = FamilyLinkStore.load(context)
        assertEquals(FamilySyncState.OFF, profile.syncState)
        assertEquals("", profile.inviteCode)
        assertNull("A family session existed without the user creating or joining one", manager().session())
        assertNull(manager().sync())
        // Loading again must still not invent a code (pre-P17 generated one on every load).
        assertEquals("", FamilyLinkStore.load(context).inviteCode)
    }

    @Test fun preP17AutomaticRegistrationIsPausedUntilConfirmed() {
        context.getSharedPreferences("ab_file_pro_family_link", Context.MODE_PRIVATE).edit().putString("invite_code", "482913").commit()
        FamilySyncStateStore.saveDeviceToken(context, "482913", token)
        val profile = FamilyLinkStore.load(context)
        assertEquals(FamilySyncState.LEGACY_PAUSED, profile.syncState)
        assertNull("A paused legacy link must not sync", manager().session())
        manager().confirmLegacyFamily(shareHealth = false)
        val session = manager().session()
        assertNotNull(session)
        assertEquals("482913", session!!.familyCode)
        assertEquals(false, FamilyLinkStore.load(context).shareHealth)
    }

    @Test fun preP17CodeWithoutRegistrationIsDiscarded() {
        context.getSharedPreferences("ab_file_pro_family_link", Context.MODE_PRIVATE).edit().putString("invite_code", "111222").commit()
        val profile = FamilyLinkStore.load(context)
        assertEquals(FamilySyncState.OFF, profile.syncState)
        assertEquals("", profile.inviteCode)
    }

    @Test fun turningHealthSharingOffWithdrawsOnlyThisPhonesOwnHealthItems() {
        val repo = AssistantRepository(context)
        var entries = repo.add(emptyList(), AssistantModule.HEALTH, "Own medicine")
        entries = repo.add(entries, AssistantModule.HEALTH, "Received medicine")
        val own = entries.first { it.title == "Own medicine" }
        val received = entries.first { it.title == "Received medicine" }
        FamilySyncStateStore.markRemoteOrigin(context, received.id)
        manager().setShareHealth(false)
        val tombstones = FamilySyncStateStore.tombstones(context)
        assertTrue("Own health item was not withdrawn", own.id in tombstones)
        assertTrue("Another member's item must not be withdrawn from here", received.id !in tombstones)
        assertEquals("Withdrawing must keep every local record", 2, repo.loadEntries().count { it.module == AssistantModule.HEALTH })
    }

    @Test fun leavingWhileNotLinkedClearsStateWithoutNetwork() {
        assertTrue(manager().leaveFamily(deleteMyData = true))
        assertEquals(FamilySyncState.OFF, FamilyLinkStore.load(context).syncState)
    }
}
