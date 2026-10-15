from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
REF = (ROOT / "app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantReferenceVisuals.kt").read_text()
MOD = (ROOT / "app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantModuleScreens.kt").read_text()
NOTES = (ROOT / "app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantNotesScreen.kt").read_text()
GRADLE = (ROOT / "app/build.gradle.kts").read_text()
VERSION = (ROOT / "VERSION").read_text().strip()
checks = {
    "reference_canvas_forced_ltr": "CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr)" in REF,
    "notes_hit_is_pdf_right_card": "RefHit(322f, 753f, 304f, 150f) { onOpenModule(AssistantModule.NOTES) }" in REF,
    "tasks_hit_is_pdf_left_card": "RefHit(18f, 753f, 295f, 150f) { onOpenModule(AssistantModule.TASKS) }" in REF,
    "notes_routes_to_real_notes_screen": "if (module == AssistantModule.NOTES && language.code == \"ar\")" in MOD and "AssistantNotesScreen(" in MOD,
    "notes_editor_large_area": "minLines = 10" in NOTES,
    "version_name": VERSION == "2.0.0-alpha55-routing-ltr-fix",
    "version_code": "versionCode = 116" in GRADLE,
}
failed=[k for k,v in checks.items() if not v]
if failed:
    raise SystemExit("ALPHA55_ROUTING_LTR_FIX: FAIL: " + ", ".join(failed))
print("ALPHA55_ROUTING_LTR_FIX: PASS")
