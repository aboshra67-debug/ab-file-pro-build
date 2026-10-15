"""Static navigation-safety contract. Not a substitute for live device tapping."""
from pathlib import Path
import re
root=Path(__file__).resolve().parents[1]
pa=(root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/PersonalAssistantScreen.kt').read_text()
home=(root/'app/src/main/java/com/abfilepro/app/ui/screens/HomeScreen.kt').read_text()
modules=(root/'app/src/main/java/com/abfilepro/app/assistant/AssistantModels.kt').read_text()
checks={
 'alpha98_version_preserved':(root/'VERSION').read_text().strip()=='2.0.0-alpha98-prayer-ui-reference-polish',
 'app_package_unchanged':'applicationId = "com.abfilepro.app"' in (root/'app/build.gradle.kts').read_text(),
 'root_assistant_uses_existing_exit': 'onBack = { showPersonalAssistant = false }' in home,
 'native_back_goes_to_modules': 'if (selectedModule != null) selectedModule = null else onBack()' in pa,
 'every_module_wrapped': pa.count('AssistantModuleScreen(')==1 and 'AssistantModuleNavigationStrip(' in pa,
 'navigation_back_targets_assistant_hub':'onBackToModules = { selectedModule = null }' in pa,
 'navigation_home_targets_app_root':'onAppHome = onBack' in pa,
 'original_module_back_kept':'onBack = { selectedModule = null }' in pa,
 'ui_back_button_fires':'onClick = onBackToModules' in pa,
 'ui_home_button_fires':'onClick = onAppHome' in pa,
 'arabic_back_text':'"رجوع للمساعد"' in pa,
 'arabic_home_text':'"الرئيسية"' in pa,
 'saved_entries_unmodified':'repository.savePrayerSettings(value)' in pa and 'repository.update(entries, entry)' in pa,
 'arabic_reference_hub_preserved':'AssistantReferenceHome(' in pa and 'scrollState = arabicHomeScrollState' in pa,
 'foreign_hub_preserved':'LazyVerticalGrid(' in pa and 'state = nativeGridState' in pa,
 'no_new_permission_required':'android.permission' not in pa,
}
for key,value in checks.items(): print(f"[{'PASS' if value else 'FAIL'}] {key}")
if not all(checks.values()): raise SystemExit('Navigation patch regression')
print(f'Alpha98 navigation safe patch: {len(checks)}/{len(checks)} PASS')
