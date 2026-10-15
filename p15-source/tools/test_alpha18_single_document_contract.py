from pathlib import Path
import hashlib
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SCAN = ROOT / 'app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt'
CAMERA = ROOT / 'app/src/main/java/com/abfilepro/app/ui/screens/ScannerProCamera.kt'
PROCESSOR = ROOT / 'app/src/main/java/com/abfilepro/app/core/ScanProcessor.kt'
GRADLE = ROOT / 'app/build.gradle.kts'
VERSION = ROOT / 'VERSION'
STRINGS = ROOT / 'app/src/main/res/values/strings_alpha18.xml'

EXPECTED_PROTECTED = {
    ROOT / 'app/src/main/java/com/abfilepro/app/MainActivity.kt': '0cd0d76e4de33fb3ea18542e70cbc28247375a1baf40afa8bd3656dbf5f69517',
    ROOT / 'app/src/main/java/com/abfilepro/app/core/FileUtils.kt': '7996c31f5228a63396056deeaa78ba53a65cfba469c3ad42ea2c4220c20e5add',
    ROOT / 'app/src/main/java/com/abfilepro/app/ui/screens/FilesScreen.kt': 'ea97c1c957a21201328668a6342fc1950a9121470d363408bb925e2a0424b6d4',
}


def must(text, needle, label):
    assert needle in text, f'MISSING: {label}: {needle}'
    print('PASS', label)


def sha256(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()

scan = SCAN.read_text(encoding='utf-8')
camera = CAMERA.read_text(encoding='utf-8')
processor = PROCESSOR.read_text(encoding='utf-8')
gradle = GRADLE.read_text(encoding='utf-8')

must(gradle, 'versionCode = 68', 'alpha18 versionCode')
must(gradle, 'versionName = "2.0.0-alpha18"', 'alpha18 versionName')
assert VERSION.read_text(encoding='utf-8').strip() == '2.0.0-alpha18'
print('PASS VERSION file')

for path, expected in EXPECTED_PROTECTED.items():
    actual = sha256(path)
    assert actual == expected, f'PROTECTED FILE CHANGED: {path.name}: {actual}'
    print('PASS protected', path.name)

# State machine remains explicit and Document stays on the custom CameraX route.
must(scan, 'private enum class ScanUiStage { HUB, CAMERA, REVIEW, SAVING, SUCCESS }', 'state machine stages')
must(scan, 'SmartScanAction.DOCUMENT -> launchMode(ScanCaptureMode.DOCUMENT)', 'document route')
must(scan, 'ScannerProCamera(', 'custom camera route')

# Camera requirements.
must(camera, 'var documentBoundsMode', 'document/full-frame state')
must(camera, 'effectiveDocumentDetection', 'effective detection switch')
must(camera, 'alpha18_camera_bounds_mode', 'document bounds UI')
must(camera, 'alpha18_camera_full_mode', 'full frame UI')
must(camera, 'autoCapture = false', 'disable auto in full-frame mode')
must(camera, 'onImportRequested', 'gallery import retained')
must(camera, 'torchEnabled', 'flash retained')

# Editor and annotation requirements.
must(processor, 'data class ScanTextAnnotation(', 'text annotation model')
must(processor, 'val annotations: List<ScanTextAnnotation> = emptyList()', 'annotations in page draft')
must(processor, 'private fun drawAnnotations(', 'annotation rasterization')
must(processor, 'bitmap.isMutable', 'safe mutable bitmap annotation render')
must(scan, 'private enum class ScanEditorSection { FILTERS, ADJUST, CROP, TEXT }', 'text editor section')
must(scan, 'alpha18_undo', 'undo control')
must(scan, 'alpha18_redo', 'redo control')
must(scan, 'alpha18_before', 'before compare control')
must(scan, 'alpha18_after', 'after compare control')
must(scan, 'ScanAnnotationOverlay(', 'movable annotation overlay')
must(scan, 'onMoveStart = { beginContinuousEdit() }', 'single undo step per text drag')
must(scan, 'PerspectiveQuadEditor(', 'four-corner editor retained')
must(scan, 'ScanFilter.entries.forEach', 'filter family retained')

# Save/review requirements.
must(scan, 'private enum class ScanOutputFormat { PDF, JPG }', 'pdf/jpg output format')
must(processor, 'suspend fun saveSinglePageJpeg(', 'jpg saver')
must(processor, 'preserveSourceDetail: Boolean = false', 'single-document PDF detail flag')
must(scan, 'singleDocumentOutputFormat == ScanOutputFormat.JPG', 'jpg save branch')
must(scan, 'preserveSourceDetail = selectedMode == ScanCaptureMode.DOCUMENT', 'document PDF detail wiring')
must(scan, 'alpha18_file_name', 'filename editor')
must(scan, 'alpha18_cancel_title', 'explicit cancel confirmation')
must(scan, 'if (selectedMode == ScanCaptureMode.DOCUMENT && reviewPages.isNotEmpty())', 'document back preserves draft')
must(scan, 'flowStage = ScanUiStage.CAMERA', 'back-to-camera path')

# Post-save actions.
must(scan, 'FileUtils.shareFileToWhatsApp', 'WhatsApp direct share')
must(scan, 'shareToMessenger(context, file)', 'Messenger direct share')
must(scan, 'FileUtils.openFile(context, file)', 'JPG open path')
must(scan, 'onNewScan', 'new scan action')

# Alpha18 localization resources parse and have all 3 language suffixes for stable keys used directly.
ET.parse(STRINGS)
resource_names = {e.attrib['name'] for e in ET.parse(STRINGS).getroot() if e.tag == 'string'}
keys = sorted(set(re.findall(r'"(alpha18_[A-Za-z0-9_]+)"', scan + camera)))
for key in keys:
    if key.endswith('_'):
        continue
    for suffix in ('ar', 'en', 'fr'):
        assert f'{key}_{suffix}' in resource_names, f'MISSING STRING: {key}_{suffix}'
print('PASS alpha18 localization resources')

print('ALPHA18_SINGLE_DOCUMENT_CONTRACT: PASS')
