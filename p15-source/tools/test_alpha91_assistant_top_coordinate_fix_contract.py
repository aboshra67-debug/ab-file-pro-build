from pathlib import Path

root = Path(__file__).resolve().parents[1]
ui = (root / "app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantReferenceVisuals.kt").read_text(encoding="utf-8")
gradle = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
version = (root / "VERSION").read_text(encoding="utf-8").strip()
checks = [
    ("version", version == "2.0.0-alpha91-assistant-top-coordinate-fix"),
    ("versionCode", "versionCode = 152" in gradle),
    ("versionName", 'versionName = "2.0.0-alpha91-assistant-top-coordinate-fix"' in gradle),
    ("classic home", "R.drawable.pa_ref_home_top" in ui),
    ("parent LTR before canvas", "CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {\n            BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(REF_W / REF_H))" in ui),
    ("category live strip", "ClassicCategoryStrip(" in ui),
    ("summary live metrics", ui.count("ClassicSummaryMetric(") >= 4),
    ("nearest live strip", "ClassicNextItemStrip(" in ui),
    ("prayer live overlay", "PrayerHomeLiveOverlay(" in ui),
    ("scroll restore path retained", "scrollState: ScrollState" in ui),
]
failed=[name for name,ok in checks if not ok]
for name,ok in checks:
    print(f"[{'PASS' if ok else 'FAIL'}] {name}")
if failed:
    raise SystemExit("Failed: " + ", ".join(failed))
print(f"Alpha91 contract: {len(checks)}/{len(checks)} PASS")
