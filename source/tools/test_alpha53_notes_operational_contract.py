from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
notes = (ROOT / 'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantNotesScreen.kt').read_text()
models = (ROOT / 'app/src/main/java/com/abfilepro/app/assistant/AssistantModels.kt').read_text()
repo = (ROOT / 'app/src/main/java/com/abfilepro/app/assistant/AssistantRepository.kt').read_text()
router = (ROOT / 'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantModuleScreens.kt').read_text()
gradle = (ROOT / 'app/build.gradle.kts').read_text()

required_notes = [
    'internal fun AssistantNotesScreen(',
    'ابحث في الملاحظات',
    'AssistantCategory.ALL to "الكل"',
    'AssistantCategory.PERSONAL to "شخصي"',
    'AssistantCategory.WORK to "عمل"',
    '📌 المثبتة',
    'كل الملاحظات',
    'إضافة ملاحظة',
    'NoteEditorDialog(',
    'onUpdate(note.copy(pinned = false))',
    'onUpdate(note.copy(pinned = true))',
    'DropdownMenuItem(',
    'حذف الملاحظة',
]
for token in required_notes:
    assert token in notes, token
assert 'val pinned: Boolean = false' in models
assert 'pinned = value.optBoolean("pinned", false)' in repo
assert 'put("pinned", entry.pinned)' in repo
assert 'module == AssistantModule.NOTES && language.code == "ar"' in router
assert 'AssistantNotesScreen(' in router
assert 'versionCode = 114' in gradle
assert 'versionName = "2.0.0-alpha53-notes-operational"' in gradle
for asset in ['pa_notes_house.webp','pa_notes_bulb.webp','pa_notes_group.webp','pa_notes_cart.webp','pa_notes_book.webp','pa_notes_trip.webp']:
    assert (ROOT/'app/src/main/res/drawable-nodpi'/asset).exists(), asset
print('ALPHA53_NOTES_OPERATIONAL_CONTRACT: PASS')
