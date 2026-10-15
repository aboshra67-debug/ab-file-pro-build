from pathlib import Path
import hashlib
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SCAN = ROOT / 'app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt'
CAM = ROOT / 'app/src/main/java/com/abfilepro/app/ui/screens/ScannerProCamera.kt'
GRADLE = ROOT / 'app/build.gradle.kts'
STRINGS = ROOT / 'app/src/main/res/values/strings_alpha21.xml'

scan = SCAN.read_text(encoding='utf-8')
cam = CAM.read_text(encoding='utf-8')
gradle = GRADLE.read_text(encoding='utf-8')
version = (ROOT / 'VERSION').read_text(encoding='utf-8').strip()


def require(cond: bool, msg: str) -> None:
    if not cond:
        raise AssertionError(msg)


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def test_identity() -> None:
    require('applicationId = "com.abfilepro.app"' in gradle, 'applicationId changed')
    require('versionCode = 72' in gradle, 'versionCode must be 72')
    require('versionName = "2.0.0-alpha22"' in gradle, 'versionName must be alpha22')
    require(version == '2.0.0-alpha22', 'VERSION mismatch')


def test_simple_document_camera() -> None:
    require('val simpleDocumentCapture = mode == ScanCaptureMode.DOCUMENT' in cam,
            'DOCUMENT simple-camera gate missing')
    require('val effectiveDocumentDetection = mode.documentDetection && !simpleDocumentCapture' in cam,
            'live detection must be disabled for DOCUMENT')
    require('if (simpleDocumentCapture)' in cam and 'preview,\n                                                capture' in cam,
            'DOCUMENT must bind preview + capture only')
    require('documentBoundsMode' not in cam, 'old bounds/full-frame camera toggle still present')
    require('alpha21_camera_simple_hint' in cam, 'simple capture hint missing')


def test_post_capture_processing() -> None:
    require('fun openProcessedSingleDocumentReview' in scan, 'post-capture processor missing')
    require('StaticDocumentDetector.detect(bitmap).quad' in scan, 'static document detection missing')
    require('filter = ScanFilter.DOCUMENT' in scan, 'automatic document enhancement missing')
    require('contrast = 1.08f' in scan and 'sharpness = .16f' in scan,
            'automatic enhancement settings missing')
    require('openProcessedSingleDocumentReview(capturedPage)' in scan,
            'camera capture does not route to processed review')
    require('Only the explicit Retake action is allowed to reopen CameraX.' in scan, 'single-document back fix marker missing')
    review_back = scan[scan.index('onBack = {', scan.index('ScanReviewContent(')):scan.index('ScanUiStage.SUCCESS', scan.index('ScanReviewContent('))]
    require('flowStage = ScanUiStage.HUB' in review_back, 'single-document review Back must return to HUB')
    require('singleDocumentFallbackBackup = page' in scan and 'flowStage = ScanUiStage.CAMERA' in scan, 'explicit retake must still route to camera')


def test_real_review_actions() -> None:
    for key in ('alpha21_quick_enhance', 'alpha21_smart_crop', 'alpha21_original'):
        require(key in scan, f'missing review action {key}')
    require('onUpdate(page.copy(perspective = quad))' in scan, 'smart crop does not apply perspective')
    require('filter = ScanFilter.ORIGINAL' in scan and 'perspective = null' in scan,
            'original action does not restore image processing')
    require('ScanProcessor.renderPreview(page)' in scan, 'review does not render real processing')
    review_fn = scan[scan.index('private fun ScanReviewContent('):scan.index('private fun ScanReviewSaveBar(')]
    require(review_fn.index('if (singleDocument) {') < review_fn.index('OutlinedTextField('), 'single-document image card must be placed before save settings')
    require('items(pages.size, key = { pages[it].id }) { index -> reviewCard(index) }' in review_fn, 'image-first review card route missing')


def test_save_and_share_paths_preserved() -> None:
    require('ScanProcessor.saveSinglePageJpeg' in scan, 'JPG save path missing')
    require('ScanProcessor.createPdf' in scan, 'PDF save path missing')
    require('FileUtils.shareFile(context, file)' in scan, 'share path missing')
    require('FileUtils.shareFileToWhatsApp(context, file)' in scan, 'WhatsApp path missing')
    require('shareToMessenger(context, file)' in scan, 'Messenger path missing')


def test_strings_and_protected_files() -> None:
    ET.parse(STRINGS)
    txt = STRINGS.read_text(encoding='utf-8')
    for key in ('alpha21_camera_simple_hint', 'alpha21_quick_enhance', 'alpha21_smart_crop', 'alpha21_original'):
        for lang in ('ar', 'en', 'fr'):
            require(f'name="{key}_{lang}"' in txt, f'missing {key}_{lang}')
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
    print(f'ALPHA22_BACK_REVIEW_CONTRACT: {len(tests)}/{len(tests)} PASS')
