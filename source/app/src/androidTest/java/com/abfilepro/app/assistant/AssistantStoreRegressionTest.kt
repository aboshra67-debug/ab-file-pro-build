package com.abfilepro.app.assistant

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** P20: one assistant store — no silent cap, no stale overwrite, safe migration and backup. */
@RunWith(AndroidJUnit4::class)
class AssistantStoreRegressionTest {
    private lateinit var context: Context
    private lateinit var repo: AssistantRepository
    private val storeDir get() = File(context.filesDir, "assistant")

    private fun resetStore() {
        storeDir.deleteRecursively()
        AssistantStore.resetCacheForTests()
    }

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ab_personal_assistant", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("ab_file_pro_family_cloud_sync", Context.MODE_PRIVATE).edit().clear().commit()
        resetStore()
        repo = AssistantRepository(context)
    }

    @After fun tearDown() {
        context.getSharedPreferences("ab_personal_assistant", Context.MODE_PRIVATE).edit().clear().commit()
        resetStore()
    }

    private fun entry(i: Int) = AssistantEntry(id = "e$i", module = AssistantModule.NOTES, title = "Note $i", createdAt = i.toLong())

    @Test fun recordsAreNotDroppedAfterTwoThousand() {
        repo.replaceEntries((1..2_100).map(::entry))
        val after = repo.add(repo.loadEntries(), AssistantModule.NOTES, "One more")
        assertEquals(2_101, after.size)
        AssistantStore.resetCacheForTests()
        assertEquals("Records were lost after reopening", 2_101, AssistantRepository(context).loadEntries().size)
    }

    @Test fun staleScreenCopyCannotUndoAChangeMadeElsewhere() {
        val screenCopy = repo.add(emptyList(), AssistantModule.HEALTH, "Medicine")
        val med = screenCopy.single()
        // e.g. the medicine alarm moved the next dose while the screen still showed the old list
        repo.update(repo.loadEntries(), med.copy(dueAt = 123_456L))
        val afterScreenAction = repo.add(screenCopy, AssistantModule.NOTES, "Shopping idea")
        assertEquals(123_456L, afterScreenAction.first { it.id == med.id }.dueAt)
        assertEquals(2, afterScreenAction.size)
    }

    @Test fun updatingARecordDeletedElsewhereDoesNotBringItBack() {
        val list = repo.add(emptyList(), AssistantModule.TASKS, "Task")
        val task = list.single()
        repo.delete(repo.loadEntries(), task.id)
        val after = repo.update(list, task.copy(title = "Edited on an old screen"))
        assertTrue(after.none { it.id == task.id })
    }

    @Test fun preP20RecordsAreMigratedAndTheOldCopyIsKept() {
        val legacy = JSONObject().put("schema", 1).put("entries", JSONArray().put(
            JSONObject().put("id", "old-1").put("module", AssistantModule.NOTES.storageId).put("title", "From P19").put("createdAt", 5L)))
        context.getSharedPreferences("ab_personal_assistant", Context.MODE_PRIVATE).edit().putString("assistant_data_v1", legacy.toString()).commit()
        resetStore()
        val loaded = AssistantRepository(context).loadEntries()
        assertEquals(listOf("old-1"), loaded.map { it.id })
        assertTrue("The pre-P20 copy must stay as a fallback",
            context.getSharedPreferences("ab_personal_assistant", Context.MODE_PRIVATE).getString("assistant_data_v1", null) != null)
    }

    @Test fun backupImportAddsOnlyMissingRecordsAndChangesNothingElse() {
        repo.replaceEntries((1..5).map(::entry))
        val backup = File(context.cacheDir, "p20_backup_${System.nanoTime()}.json")
        try {
            assertEquals(5, AssistantBackup.export(context, Uri.fromFile(backup)))
            repo.delete(repo.loadEntries(), "e3")
            repo.update(repo.loadEntries(), entry(1).copy(title = "Edited after backup"))
            val result = AssistantBackup.import(context, Uri.fromFile(backup))
            assertEquals(1, result.added)
            assertEquals(4, result.alreadyPresent)
            val now = repo.loadEntries()
            assertEquals(5, now.size)
            assertEquals("Edited after backup", now.first { it.id == "e1" }.title)
        } finally { backup.delete() }
    }

    @Test fun familySyncAppliesOnlyChangedRecords() {
        repo.replaceEntries(listOf(entry(1), AssistantEntry(id = "f1", module = AssistantModule.FAMILY_REQUESTS, title = "Bread")))
        repo.update(repo.loadEntries(), entry(1).copy(title = "Local edit during sync"))
        val merged = repo.applyRemote(listOf(AssistantEntry(id = "f2", module = AssistantModule.FAMILY_REQUESTS, title = "Milk")), setOf("f1"))
        assertEquals(setOf("e1", "f2"), merged.map { it.id }.toSet())
        assertEquals("Local edit during sync", merged.first { it.id == "e1" }.title)
    }
}
