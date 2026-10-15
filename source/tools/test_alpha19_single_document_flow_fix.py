from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
scan = (ROOT / "app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt").read_text(encoding="utf-8")
gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
assert "versionCode = 69" in gradle
assert 'versionName = "2.0.0-alpha19"' in gradle
assert "a single-document capture must leave the camera immediately" in scan
assert "selectedMode == ScanCaptureMode.DOCUMENT || selectedMode == ScanCaptureMode.TRANSLATION" in scan
assert "if (singleDocument) confirmCancel = true else onBack()" in scan
assert "Retake is the only path back to the camera" in scan
# protected files remain untouched by this patch (verified separately by hashes)
print("ALPHA19_SINGLE_DOCUMENT_FLOW_FIX_CONTRACT_PASS")
