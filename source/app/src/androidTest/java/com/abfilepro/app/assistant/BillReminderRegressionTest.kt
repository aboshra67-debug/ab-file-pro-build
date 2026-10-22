package com.abfilepro.app.assistant

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** P25: bill reminders ring on a real AlarmManager and travel with the assistant backup. */
@RunWith(AndroidJUnit4::class)
class BillReminderRegressionTest {
    private lateinit var context: Context
    private val billId = 424242L

    @Before fun clean() {
        context = ApplicationProvider.getApplicationContext()
        wipe()
    }

    @After fun cleanup() = wipe()

    private fun wipe() {
        BillAlarmScheduler.cancel(context, billId)
        context.getSharedPreferences(BillReminderRecords.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("ab_file_pro_reminder_ledger_v18", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun storeBill(dueInDays: Int) {
        val due = System.currentTimeMillis() + dueInDays * BillReminderPolicy.DAY_MS
        val array = JSONArray().put(JSONObject().put("id", billId).put("type", "WATER").put("due", due).put("account", "A-1").put("provider", ""))
        context.getSharedPreferences(BillReminderRecords.PREFS, Context.MODE_PRIVATE).edit().putString(BillReminderRecords.KEY, array.toString()).commit()
    }

    @Test fun aBillGetsBothAlarmsAndDeletingItRemovesThem() {
        storeBill(dueInDays = 5)
        val bill = BillReminderRecords.find(context, billId)!!
        BillAlarmScheduler.sync(context, bill.id, bill.dueAt)
        assertTrue("No day-before alarm", BillAlarmScheduler.isScheduled(context, billId, BillReminderPolicy.Kind.TOMORROW))
        assertTrue("No due-day alarm", BillAlarmScheduler.isScheduled(context, billId, BillReminderPolicy.Kind.TODAY))
        BillAlarmScheduler.cancel(context, billId)
        assertFalse(BillAlarmScheduler.isScheduled(context, billId, BillReminderPolicy.Kind.TOMORROW))
        assertFalse(BillAlarmScheduler.isScheduled(context, billId, BillReminderPolicy.Kind.TODAY))
    }

    @Test fun backupCarriesBillRemindersAndRestoringThemRearmsTheAlarms() {
        storeBill(dueInDays = 5)
        val backup = File(context.cacheDir, "p25_backup_${System.nanoTime()}.json")
        try {
            AssistantBackup.export(context, Uri.fromFile(backup))
            wipe()
            assertEquals(0, BillReminderRecords.load(context).size)
            val result = AssistantBackup.import(context, Uri.fromFile(backup))
            assertTrue("The bill reminders were not restored", result.settingsRestored >= 1)
            assertEquals(listOf(billId), BillReminderRecords.load(context).map { it.id })
            assertTrue("The restored bill has no alarm", BillAlarmScheduler.isScheduled(context, billId, BillReminderPolicy.Kind.TODAY))
        } finally { backup.delete() }
    }
}
