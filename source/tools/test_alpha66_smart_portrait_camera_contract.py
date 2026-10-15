from pathlib import Path

root = Path(__file__).resolve().parents[1]
smart = (root / "app/src/main/java/com/abfilepro/app/ui/screens/assistant/SmartPortraitCamera.kt").read_text()
modules = (root / "app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantModuleScreens.kt").read_text()
gradle = (root / "app/build.gradle.kts").read_text()

checks = {
    "version": 'versionName = "2.0.0-alpha66-smart-portrait-camera"' in gradle and 'versionCode = 127' in gradle,
    "face_dependency": 'com.google.mlkit:face-detection:16.1.7' in gradle,
    "modes": all(name in smart for name in ["SELFIE", "CLOSE", "HALF_BODY", "FULL_BODY", "FORMAL", "GROUP"]),
    "guidance": all(token in smart for token in ["MOVE_CLOSER", "STEP_BACK", "RAISE_PHONE", "LOW_LIGHT", "HOLD_STILL", "READY"]),
    "auto_capture": "analysis.captureReady" in smart and "captureNow(useTimer = false)" in smart,
    "camera_controls": all(token in smart for token in ["GridOn", "Timer", "FlashAuto", "Cameraswitch", "setZoomRatio"]),
    "filters": all(name in smart for name in ["NATURAL", "SOFT", "WARM", "COOL", "CINEMATIC", "MONO", "PRO"]),
    "editor": all(token in smart for token in ["autoEnhance", "faceLight", "PortraitCrop", "saveToGallery", "writeShareCopy"]),
    "gallery_editor": "SmartPortraitGalleryEditorScreen" in modules,
    "scanner_fallback": "onOpenScanner" in modules and "فتح ماسح المستندات بدلًا من ذلك" in modules,
    "camera_direct_operational": "if (module == AssistantModule.CAMERA)" in modules,
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(f"[{'PASS' if ok else 'FAIL'}] {name}")
if failed:
    raise SystemExit("Alpha66 contract failed: " + ", ".join(failed))
print("Alpha66 Smart Portrait Camera contract: PASS")
