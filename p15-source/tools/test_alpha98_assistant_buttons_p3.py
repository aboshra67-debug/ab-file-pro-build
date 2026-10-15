from pathlib import Path
root=Path(__file__).resolve().parents[1]
buttons=(root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantButtonStyles.kt').read_text()
pa=(root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/PersonalAssistantScreen.kt').read_text()
notes=(root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantNotesScreen.kt').read_text()
tasks=(root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantTasksScreen.kt').read_text()
checks={
 'shared_button_file_created':'AssistantTopStripButton' in buttons and 'AssistantPillTabButton' in buttons and 'AssistantExtendedFabButton' in buttons,
 'top_strip_uses_shared_buttons':'AssistantTopStripButton(' in pa,
 'notes_header_unified':'AssistantHeaderCircleButton(' in notes,
 'notes_fab_unified':'AssistantExtendedFabButton(' in notes,
 'notes_category_tabs_unified':'AssistantPillTabButton(' in notes,
 'tasks_fab_unified':'AssistantExtendedFabButton(' in tasks,
 'tasks_tabs_unified':'TaskSegmentTabs(' in tasks and 'AssistantPillTabButton(' in tasks,
 'assistant_nav_targets_preserved':'onBackToModules = { selectedModule = null }' in pa and 'onAppHome = onBack' in pa,
}
for k,v in checks.items(): print(f"[{'PASS' if v else 'FAIL'}] {k}")
if not all(checks.values()): raise SystemExit('Assistant buttons P3 regression')
print(f'Alpha98 assistant buttons P3: {len(checks)}/{len(checks)} PASS')
