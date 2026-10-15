from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
scan = (ROOT / "app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt").read_text(encoding="utf-8")
detector = (ROOT / "app/src/main/java/com/abfilepro/app/core/StaticDocumentDetector.kt").read_text(encoding="utf-8")
gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
version = (ROOT / "VERSION").read_text(encoding="utf-8").strip()

assert "versionCode = 70" in gradle
assert 'versionName = "2.0.0-alpha20"' in gradle
assert version == "2.0.0-alpha20"
assert "private enum class ScanUiStage { HUB, CAMERA, BOUNDS, REVIEW, SAVING, SUCCESS }" in scan
assert "StaticDocumentDetector.detect(bitmap).quad" in scan
assert "openSingleDocumentBounds(capturedPage)" in scan
assert "SingleDocumentBoundsContent(" in scan
assert "PerspectiveQuadEditor(" in scan
assert "flowStage = ScanUiStage.BOUNDS" in scan
assert "leaveBoundsForCamera(deleteCurrent = true)" in scan
assert "A manual corner drag wins over an in-flight automatic detector result" in scan
assert "if (singleDocument) confirmCancel = true else onBack()" not in scan
assert "var confirmCancel" not in scan
assert 'alpha18_cancel_title' not in scan
assert "Post-capture detection is intentionally a little more permissive" in detector
assert "confidence >= .48f" in detector
print("ALPHA20_DOCUMENT_BOUNDS_CONTRACT_PASS")
