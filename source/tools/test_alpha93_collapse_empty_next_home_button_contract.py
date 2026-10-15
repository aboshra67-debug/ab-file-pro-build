from pathlib import Path
root = Path(__file__).resolve().parents[1]
ui = (root / "app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantReferenceVisuals.kt").read_text(encoding="utf-8")
gradle = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
version = (root / "VERSION").read_text(encoding="utf-8").strip()
asset = root / "app/src/main/res/drawable-nodpi/pa_ref_home_top_no_next.webp"
checks = [
    ("version", version == "2.0.0-alpha93-collapse-empty-next-home-button"),
    ("versionCode", "versionCode = 154" in gradle),
    ("versionName", 'versionName = "2.0.0-alpha93-collapse-empty-next-home-button"' in gradle),
    ("classic home retained", "R.drawable.pa_ref_home_top" in ui),
    ("no-next compact asset", "R.drawable.pa_ref_home_top_no_next" in ui and asset.exists()),
    ("conditional compact layout", "if (hasNextEntry)" in ui and "REF_H_NO_NEXT" in ui),
    ("empty label absent", "لا توجد مواعيد قريبة" not in ui),
    ("home button", "ClassicHomeButton(" in ui and 'contentDescription = "الرئيسية"' in ui),
    ("filters retained", "ClassicCategoryStrip(" in ui),
    ("prayer retained", "PrayerHomeLiveOverlay(" in ui),
    ("scroll restore retained", "scrollState: ScrollState" in ui),
]
failed=[name for name,ok in checks if not ok]
for name,ok in checks:
    print(f"[{'PASS' if ok else 'FAIL'}] {name}")
if failed:
    raise SystemExit("Failed: " + ", ".join(failed))
print(f"Alpha93 contract: {len(checks)}/{len(checks)} PASS")
