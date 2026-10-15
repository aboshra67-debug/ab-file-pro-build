from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REF = (ROOT / "app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantReferenceVisuals.kt").read_text()
NOTES = (ROOT / "app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantNotesScreen.kt").read_text()
GRADLE = (ROOT / "app/build.gradle.kts").read_text()
VERSION = (ROOT / "VERSION").read_text().strip()

checks = {
    "absolute_pdf_hit_coordinates": ".absoluteOffset(" in REF,
    "notes_hit_right_visual_card": "RefHit(322f, 753f, 304f, 150f) { onOpenModule(AssistantModule.NOTES) }" in REF,
    "tasks_hit_left_visual_card": "RefHit(18f, 753f, 295f, 150f) { onOpenModule(AssistantModule.TASKS) }" in REF,
    "full_width_note_editor": "DialogProperties(usePlatformDefaultWidth = false)" in NOTES,
    "large_writing_area": "Modifier.fillMaxWidth().weight(1f)" in NOTES and "minLines = 10" in NOTES,
    "font_size_controls": "coerceAtLeast(14)" in NOTES and "coerceAtMost(30)" in NOTES,
    "font_family_controls": "FontFamily.Serif" in NOTES and "FontFamily.Monospace" in NOTES and '"عادي"' in NOTES,
    "text_color_controls": '"red" to Color' in NOTES and '"blue" to Color' in NOTES and '"green" to Color' in NOTES and '"purple" to Color' in NOTES,
    "style_persisted_for_new_notes": "onAdd(title, details, noteCategory, null, null, styleData)" in NOTES,
    "style_persisted_for_existing_notes": "secondary = styleData" in NOTES,
    "style_metadata_versioned": '"abnote:v1|size=' in NOTES,
    "version_name": VERSION == "2.0.0-alpha54-notes-editor-routing",
    "version_code": "versionCode = 115" in GRADLE,
}

failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit("ALPHA54_NOTES_EDITOR_ROUTING: FAIL: " + ", ".join(failed))
print("ALPHA54_NOTES_EDITOR_ROUTING: PASS")
