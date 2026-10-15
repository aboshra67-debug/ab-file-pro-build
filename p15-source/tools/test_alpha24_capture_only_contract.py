from pathlib import Path
import hashlib

ROOT = Path(__file__).resolve().parents[1]
CAM = ROOT / 'app/src/main/java/com/abfilepro/app/ui/screens/ScannerProCamera.kt'
GRADLE = ROOT / 'app/build.gradle.kts'
VERSION = ROOT / 'VERSION'

cam = CAM.read_text(encoding='utf-8')
gradle = GRADLE.read_text(encoding='utf-8')
version = VERSION.read_text(encoding='utf-8').strip()


def require(cond, msg):
    if not cond:
        raise AssertionError(msg)


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def test_identity():
    require('versionCode = 74' in gradle, 'versionCode must be 74')
    require('versionName = "2.0.0-alpha24"' in gradle, 'versionName must be alpha24')
    require(version == '2.0.0-alpha24', 'VERSION mismatch')


def test_single_document_has_local_capture_preview_state():
    require('capturedDocumentFile' in cam, 'captured document file state missing')
    require('capturedDocumentPreview' in cam, 'captured document preview state missing')
    require('clearCapturedDocumentPreview' in cam, 'retake cleanup helper missing')


def test_single_document_capture_stops_before_processing_navigation():
    saved = cam[cam.index('override fun onImageSaved'):cam.index('override fun onError')]
    doc_pos = saved.index('if (simpleDocumentCapture)')
    doc_block = saved[doc_pos:saved.index('val pages =', doc_pos)]
    require('output.exists() && output.length() > 0L' in doc_block, 'raw capture file validation missing')
    require('loadCapturedDocumentPreview(output)' in doc_block, 'raw capture preview loader missing')
    helper = cam[cam.index('fun loadCapturedDocumentPreview'):cam.index('fun remainingCapacity')]
    require('ScanProcessor.renderSourcePreview' in helper, 'raw capture preview decode missing')
    require('capturedDocumentPreview =' in doc_block, 'preview state is not updated')
    require('onPagesCaptured' not in doc_block, 'single-document Alpha24 must not navigate after capture')
    require('prepareCameraCapture' not in doc_block, 'single-document Alpha24 must not enter processing pipeline')
    require('return' in doc_block, 'single-document capture must stop locally after preview')


def test_single_document_preview_ui_and_retake():
    require('val capturedPreview = capturedDocumentPreview' in cam, 'captured preview UI state read missing')
    require('capturedPreview.asImageBitmap()' in cam, 'captured preview image is not rendered')
    require('alpha24_capture_success' in cam, 'capture success message missing')
    require('alpha24_retake' in cam, 'retake action missing')
    require('clearCapturedDocumentPreview()' in cam, 'retake must clear local preview')


def test_back_from_preview_does_not_leave_scan_flow():
    require('if (capturedDocumentPreview != null)' in cam, 'preview back guard missing')
    require('clearCapturedDocumentPreview()' in cam, 'preview back must return to camera, not parent flow')


def test_protected_files_unchanged():
    expected = {
        ROOT / 'app/src/main/java/com/abfilepro/app/MainActivity.kt':
            '0cd0d76e4de33fb3ea18542e70cbc28247375a1baf40afa8bd3656dbf5f69517',
        ROOT / 'app/src/main/java/com/abfilepro/app/core/FileUtils.kt':
            '7996c31f5228a63396056deeaa78ba53a65cfba469c3ad42ea2c4220c20e5add',
        ROOT / 'app/src/main/java/com/abfilepro/app/ui/screens/FilesScreen.kt':
            'ea97c1c957a21201328668a6342fc1950a9121470d363408bb925e2a0424b6d4',
    }
    for path, digest in expected.items():
        require(sha(path) == digest, f'protected file changed: {path.name}')


if __name__ == '__main__':
    tests = [v for k, v in sorted(globals().items()) if k.startswith('test_') and callable(v)]
    for test in tests:
        test()
        print('PASS', test.__name__)
    print(f'ALPHA24_CAPTURE_ONLY_CONTRACT: {len(tests)}/{len(tests)} PASS')
