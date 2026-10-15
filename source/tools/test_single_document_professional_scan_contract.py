from pathlib import Path
import hashlib
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SCAN = ROOT / 'app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt'
scan = SCAN.read_text(encoding='utf-8')


def require(cond: bool, message: str) -> None:
    if not cond:
        raise AssertionError(message)


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def test_professional_document_client_and_route() -> None:
    require('singleDocumentOptions' in scan, 'missing dedicated singleDocumentOptions')
    require('.setPageLimit(1)' in scan, 'single-document scanner must set page limit to 1')
    require('GmsDocumentScannerOptions.RESULT_FORMAT_JPEG' in scan, 'single-document scanner must request JPEG')
    require('singleDocumentScanner' in scan, 'missing dedicated singleDocumentScanner')
    require('launchSingleDocumentScanner' in scan, 'missing professional single-document launch function')
    require('SmartScanAction.DOCUMENT -> launchSingleDocumentScanner()' in scan,
            'DOCUMENT must route to professional scanner')


def test_other_modes_keep_existing_routes() -> None:
    for marker in (
        'SmartScanAction.BATCH -> launchMode(ScanCaptureMode.BATCH)',
        'SmartScanAction.ID_CARD -> launchMode(ScanCaptureMode.ID_CARD)',
        'SmartScanAction.RECEIPT -> launchMode(ScanCaptureMode.RECEIPT)',
        'SmartScanAction.TRANSLATION -> launchMode(ScanCaptureMode.TRANSLATION)',
        'SmartScanAction.IMAGES_TO_PDF -> launchMode(ScanCaptureMode.PHOTO)',
    ):
        require(marker in scan, f'route changed or missing: {marker}')


def test_retake_cancel_and_fallback_state_are_explicit() -> None:
    require('singleDocumentRetakeIndex' in scan, 'retake state is missing')
    require('showSingleDocumentFallback' in scan, 'launch-failure fallback state is missing')
    require('selectedMode == ScanCaptureMode.DOCUMENT' in scan,
            'DOCUMENT-specific review behavior is missing')
    require('singleDocumentRetakeIndex = null' in scan,
            'retake state must be reset explicitly')


def test_fallback_backup_is_cleaned_on_screen_dispose() -> None:
    require('latestDocumentFallbackBackup' in scan,
            'fallback retake backup must be tracked for dispose cleanup')
    require('latestDocumentFallbackBackup?.file?.delete()' in scan,
            'fallback retake backup file must be deleted on dispose')


def test_fallback_strings_exist_and_xml_parses() -> None:
    p = ROOT / 'app/src/main/res/values/strings_single_document_professional.xml'
    require(p.exists(), 'missing dedicated fallback localization resource file')
    ET.parse(p)
    text = p.read_text(encoding='utf-8')
    for key in (
        'single_doc_scanner_unavailable_title',
        'single_doc_scanner_unavailable_message',
        'single_doc_use_camera',
        'single_doc_cancel',
    ):
        for lang in ('ar', 'en', 'fr'):
            require(f'name="{key}_{lang}"' in text, f'missing {key}_{lang}')


def test_protected_files_unchanged() -> None:
    expected = {
        ROOT / 'app/src/main/java/com/abfilepro/app/MainActivity.kt':
            '0cd0d76e4de33fb3ea18542e70cbc28247375a1baf40afa8bd3656dbf5f69517',
        ROOT / 'app/src/main/java/com/abfilepro/app/core/FileUtils.kt':
            '7996c31f5228a63396056deeaa78ba53a65cfba469c3ad42ea2c4220c20e5add',
        ROOT / 'app/src/main/java/com/abfilepro/app/ui/screens/FilesScreen.kt':
            'ea97c1c957a21201328668a6342fc1950a9121470d363408bb925e2a0424b6d4',
        ROOT / 'app/src/main/java/com/abfilepro/app/core/DocumentFrameAnalyzer.kt':
            'f2737c8342a44586161af7384fe8d6a28a02b44a737db5bed54ec8701cfae78d',
        ROOT / 'app/src/main/java/com/abfilepro/app/ui/screens/ScannerProCamera.kt':
            'ed017cf66df406578ff455a45650fb0a1c5d5003e728d87e0774ab19241f8c6d',
    }
    for path, digest in expected.items():
        require(sha(path) == digest, f'protected file changed: {path.name}')


def test_identity_unchanged() -> None:
    gradle = (ROOT / 'app/build.gradle.kts').read_text(encoding='utf-8')
    require('applicationId = "com.abfilepro.app"' in gradle, 'applicationId changed')
    require('versionCode = 63' in gradle, 'versionCode changed')
    require('versionName = "2.0.0-alpha13"' in gradle, 'versionName changed')


if __name__ == '__main__':
    tests = [v for k, v in sorted(globals().items()) if k.startswith('test_') and callable(v)]
    passed = 0
    for test in tests:
        try:
            test()
        except Exception as exc:
            print(f'FAIL {test.__name__}: {exc}')
            raise
        else:
            passed += 1
            print(f'PASS {test.__name__}')
    print(f'SINGLE_DOCUMENT_PROFESSIONAL_SCAN_CONTRACT: {passed}/{len(tests)} PASS')
