"""Static checks for safe status-bar offset on all assistant module pages.
Not a substitute for physical Android UI testing.
"""
from pathlib import Path
root=Path(__file__).resolve().parents[1]
pa=(root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/PersonalAssistantScreen.kt').read_text()
notes=(root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantNotesScreen.kt').read_text()
tasks=(root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantTasksScreen.kt').read_text()
checks={
 'statusbar_padding_imported':'import androidx.compose.foundation.layout.statusBarsPadding' in pa,
 'statusbar_applied_to_all_module_host':'Column(Modifier.fillMaxSize().background(Color(0xFFF7F9FC)).statusBarsPadding()) {' in pa,
 'no_home_padding_added':'AssistantReferenceHome(' in pa and 'nativeGridState' in pa,
 'native_back_still_goes_to_assistant':'if (selectedModule != null) selectedModule = null else onBack()' in pa,
 'back_button_still_returns_to_modules':'onBackToModules = { selectedModule = null }' in pa,
 'app_home_still_leaves_assistant':'onAppHome = onBack' in pa,
 'both_buttons_still_present':'AssistantTopStripButton(' in pa and 'icon = Icons.Default.Home' in pa,
 'notes_p3_still_present':'AssistantExtendedFabButton(' in notes and 'AssistantHeaderCircleButton(' in notes,
 'tasks_p3_still_present':'AssistantExtendedFabButton(' in tasks and 'TaskSegmentTabs(' in tasks,
}
for key, ok in checks.items(): print(f"[{'PASS' if ok else 'FAIL'}] {key}")
assert all(checks.values()), 'Alpha98 P4 status bar regression'
print(f'Alpha98 P4 status bar: {len(checks)}/{len(checks)} PASS')
