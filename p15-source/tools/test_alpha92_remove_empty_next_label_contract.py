from pathlib import Path
root = Path(__file__).resolve().parents[1]
ui = (root / "app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantReferenceVisuals.kt").read_text(encoding="utf-8")
gradle = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
version = (root / "VERSION").read_text(encoding="utf-8").strip()
checks = [
    ("version", version == "2.0.0-alpha92-remove-empty-next-label"),
    ("versionCode", "versionCode = 153" in gradle),
    ("versionName", 'versionName = "2.0.0-alpha92-remove-empty-next-label"' in gradle),
    ("classic home retained", "R.drawable.pa_ref_home_top" in ui),
    ("empty label removed", "لا توجد مواعيد قريبة" not in ui),
    ("nearest strip retained", "ClassicNextItemStrip(" in ui),
    ("filters retained", "ClassicCategoryStrip(" in ui),
    ("prayer overlay retained", "PrayerHomeLiveOverlay(" in ui),
    ("scroll restore retained", "scrollState: ScrollState" in ui),
]
failed=[name for name,ok in checks if not ok]
for name,ok in checks:
    print(f"[{'PASS' if ok else 'FAIL'}] {name}")
if failed:
    raise SystemExit("Failed: " + ", ".join(failed))
print(f"Alpha92 contract: {len(checks)}/{len(checks)} PASS")
