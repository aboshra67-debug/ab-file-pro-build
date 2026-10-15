from pathlib import Path

root = Path(__file__).resolve().parents[1]
smart = (root / "app/src/main/java/com/abfilepro/app/ui/screens/assistant/SmartPortraitCamera.kt").read_text()
gradle = (root / "app/build.gradle.kts").read_text()

checks = {
    "identity": 'versionName = "2.0.0-alpha67-smart-portrait-ui-polish"' in gradle and 'versionCode = 128' in gradle,
    "safe_insets": ".statusBarsPadding()" in smart and ".navigationBarsPadding()" in smart,
    "short_modes": "fun shortLabel" in smart and all(x in smart for x in ['"قريب"', '"نصف"', '"كامل"', '"رسمي"']),
    "professional_pills": all(x in smart for x in ["PortraitModePill", "PortraitFilterPill", "PortraitAutoCapturePill", "PortraitCircleTool"]),
    "safe_guide_area": "val topInset = 168.dp.toPx()" in smart and "val bottomInset = 230.dp.toPx()" in smart,
    "human_guide": "drawOval(" in smart and "drawArc(" in smart,
    "corner_face_markers": "val corner = 12.dp.toPx()" in smart,
    "capture_preserved": "captureNow(useTimer = true)" in smart and "captureNow(useTimer = false)" in smart,
    "analysis_preserved": "ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST" in smart and "FaceDetection.getClient" in smart,
    "editor_preserved": "PortraitEditorScreen" in smart and "saveToGallery" in smart and "writeShareCopy" in smart,
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(f"[{'PASS' if ok else 'FAIL'}] {name}")
if failed:
    raise SystemExit("Alpha67 UI contract failed: " + ", ".join(failed))
print("Alpha67 Smart Portrait UI Polish contract: PASS")
