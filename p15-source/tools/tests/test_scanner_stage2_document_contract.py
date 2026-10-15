from pathlib import Path

root = Path(__file__).resolve().parents[2]
processor = (root / 'app/src/main/java/com/abfilepro/app/core/ScanProcessor.kt').read_text(encoding='utf-8')
geometry = (root / 'app/src/main/java/com/abfilepro/app/core/DocumentCropGeometry.kt').read_text(encoding='utf-8')
editor = (root / 'app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt').read_text(encoding='utf-8')
camera = (root / 'app/src/main/java/com/abfilepro/app/ui/screens/ScannerProCamera.kt').read_text(encoding='utf-8')
strings = (root / 'app/src/main/res/values/strings_v141_b1.xml').read_text(encoding='utf-8')

assert 'private fun resolveDocumentPerspective(' in processor
assert 'detectedQuad?.clamped() ?: detectedRect?.let' in processor
assert 'DocumentCropGeometry.fromRect(' in processor
assert 'perspective = if (mode.documentDetection)' in processor
assert 'resolveDocumentPerspective(' in processor
assert 'detectedRect = detectedRect' in processor
assert 'context.uiText(language, "scanner_stage2_document_auto_status")' in editor
assert 'scanner_stage2_document_auto_status_ar' in strings
assert 'scanner_stage2_document_auto_status_en' in strings
assert 'scanner_stage2_document_auto_status_fr' in strings
assert 'PerspectiveQuadEditor(' in editor
assert 'onQuadChange = { perspective = it }' in editor
assert 'val overlayQuad = if (mode.documentDetection) metrics?.quad else null' in camera
assert 'captureReady' in camera
assert 'fun fromRect(' in geometry
print('scanner stage2 document contract: PASS')
