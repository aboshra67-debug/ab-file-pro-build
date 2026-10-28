package com.abfilepro.app.shots

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.abfilepro.app.MainActivity
import com.abfilepro.app.assistant.AssistantCategory
import com.abfilepro.app.assistant.AssistantEntry
import com.abfilepro.app.assistant.AssistantModule
import com.abfilepro.app.assistant.PrayerSettings
import com.abfilepro.app.ui.i18n.AppLanguage
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.screens.assistant.AssistantDark
import com.abfilepro.app.ui.screens.assistant.AssistantLang
import com.abfilepro.app.ui.screens.assistant.AssistantModuleScreen
import com.abfilepro.app.ui.screens.assistant.AssistantTodayScreen
import com.abfilepro.app.ui.screens.assistant.PersonalAssistantScreen
import com.abfilepro.app.ui.screens.FilesScreen
import com.abfilepro.app.ui.screens.HomeScreen
import com.abfilepro.app.ui.theme.ABFileProTheme
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P28: draws each assistant screen in dark mode (Arabic and English) and saves a screenshot to
 * /data/local/tmp/ab_dark so the dark colours can be looked at, not only reasoned about.
 * Compiled only with -PAB_DARK_SHOTS=true (see app/build.gradle.kts).
 */
@RunWith(AndroidJUnit4::class)
class DarkModeScreenshotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun shell(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
    }

    private fun sample(): List<AssistantEntry> {
        val now = System.currentTimeMillis()
        val hour = 3_600_000L
        fun e(id: String, module: AssistantModule, title: String, details: String = "", due: Long? = null, done: Boolean = false, amount: Double? = null, pinned: Boolean = false, category: AssistantCategory = AssistantCategory.PERSONAL) =
            AssistantEntry(id = id, module = module, title = title, details = details, category = category, dueAt = due, completed = done, amount = amount, pinned = pinned)
        return listOf(
            e("t1", AssistantModule.TASKS, "الاتصال بالمحامي", "قبل نهاية اليوم", now + 2 * hour),
            e("t2", AssistantModule.TASKS, "دفع إيجار المكتب", due = now - 26 * hour, category = AssistantCategory.WORK),
            e("t3", AssistantModule.TASKS, "شراء هدية", due = null),
            e("t4", AssistantModule.TASKS, "تجديد الاشتراك", due = now - 3 * hour, done = true),
            e("c1", AssistantModule.CALENDAR, "اجتماع الفريق", "غرفة 3", now + 5 * hour, amount = 15.0),
            e("c2", AssistantModule.CALENDAR, "موعد الأسنان", due = now + 30 * hour, amount = 30.0),
            e("n1", AssistantModule.NOTES, "أفكار المشروع", "نقاط سريعة عن المرحلة الجاية", pinned = true),
            e("n2", AssistantModule.NOTES, "قائمة السفر", "جواز، شاحن، تذاكر", category = AssistantCategory.WORK),
            e("m1", AssistantModule.MONEY, "غداء", amount = 120.0, due = now - hour),
            e("m2", AssistantModule.MONEY, "مواصلات", amount = 45.0, due = now - 2 * hour),
            e("h1", AssistantModule.HEALTH, "فيتامين د", "حبة بعد الأكل", now + hour),
            e("d1", AssistantModule.DOCUMENTS, "جواز السفر", due = now + 90L * 24 * hour),
            e("f1", AssistantModule.FAMILY_REQUESTS, "لبن وعيش", "للبيت", now + hour),
            e("mt1", AssistantModule.MEETINGS, "مراجعة الميزانية", due = now + 8 * hour, category = AssistantCategory.WORK),
        )
    }

    private fun show(scenario: ActivityScenario<MainActivity>, language: AppLanguage, content: @Composable () -> Unit) {
        scenario.onActivity { activity ->
            AssistantDark.on = true
            AssistantLang.language = language
            activity.setContent {
                val direction = if (language == AppLanguage.ARABIC) LayoutDirection.Rtl else LayoutDirection.Ltr
                // MainActivity's own first composition can still reset these flags after launch, so set them from inside ours too.
                SideEffect {
                    AssistantDark.on = true
                    AssistantLang.language = language
                }
                CompositionLocalProvider(LocalLayoutDirection provides direction, LocalAppLanguage provides language) {
                    ABFileProTheme(darkTheme = true) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
                    }
                }
            }
        }
    }

    private fun capture(scenario: ActivityScenario<MainActivity>, language: AppLanguage, name: String, content: @Composable () -> Unit) {
        show(scenario, language, content)
        Thread.sleep(2600)
        shell("screencap -p /data/local/tmp/ab_dark/${language.code}_$name.png")
        Log.i("DARKSHOT", "${language.code}_$name captured")
    }

    @Test
    fun captureAssistantScreensInDarkMode() {
        shell("rm -rf /data/local/tmp/ab_dark")
        shell("mkdir -p /data/local/tmp/ab_dark")
        val context = instrumentation.targetContext
        // A system permission dialog on top of the screen hides it in the screenshot, so grant them first.
        for (permission in listOf(
            "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.POST_NOTIFICATIONS", "android.permission.RECORD_AUDIO", "android.permission.CAMERA",
        )) runCatching { instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission) }
        val repository = com.abfilepro.app.assistant.AssistantRepository(context)
        var current = repository.loadEntries()
        for (s in sample()) {
            current = repository.add(current, s.module, s.title, s.details, s.category, s.dueAt, s.amount, s.secondary, enabled = false)
        }
        val entries = repository.loadEntries()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(2000)
            for (language in listOf(AppLanguage.ARABIC, AppLanguage.ENGLISH)) {
                capture(scenario, language, "home") { PersonalAssistantScreen(onBack = {}, onOpenScanner = {}) }
                capture(scenario, language, "today") {
                    AssistantTodayScreen(entries = entries, onBack = {}, onQuickAdd = {}, onUpdate = {}, onOpenModule = {})
                }
                for (module in AssistantModule.entries) {
                    capture(scenario, language, module.storageId) {
                        AssistantModuleScreen(
                            module = module,
                            entries = entries.filter { it.module == module },
                            prayerSettings = PrayerSettings(),
                            defaultCategory = AssistantCategory.PERSONAL,
                            onBack = {},
                            onOpenScanner = {},
                            onAdd = { _, _, _, _, _, _ -> },
                            onAddTask = { _, _, _, _, _ -> },
                            onUpdate = {},
                            onDelete = {},
                            onPrayerSettings = {},
                        )
                    }
                }
            }
        }
        // Extras last: a problem in these must not hide the assistant screenshots above.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(1500)
            for (language in listOf(AppLanguage.ARABIC, AppLanguage.ENGLISH)) {
                capture(scenario, language, "mainhome") { HomeScreen(onNavigate = {}, onOpenPdf = {}) }
                capture(scenario, language, "files") {
                    FilesScreen(onBack = {}, onOpenPdf = {}, onEditPdf = {}, onCleanShare = {}, onOpenSmartPack = {})
                }
            }
        }
        check(entries.isNotEmpty())
    }
}
