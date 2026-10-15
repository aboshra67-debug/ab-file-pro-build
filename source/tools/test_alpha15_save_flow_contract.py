from __future__ import annotations

from pathlib import Path
import hashlib

ROOT = Path(__file__).resolve().parents[1]

PROTECTED = {
    'app/src/main/java/com/abfilepro/app/MainActivity.kt': '0cd0d76e4de33fb3ea18542e70cbc28247375a1baf40afa8bd3656dbf5f69517',
    'app/src/main/java/com/abfilepro/app/core/FileUtils.kt': '7996c31f5228a63396056deeaa78ba53a65cfba469c3ad42ea2c4220c20e5add',
    'app/src/main/java/com/abfilepro/app/ui/screens/FilesScreen.kt': 'ea97c1c957a21201328668a6342fc1950a9121470d363408bb925e2a0424b6d4',
}


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding='utf-8')


def sha(rel: str) -> str:
    return hashlib.sha256((ROOT / rel).read_bytes()).hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def test_version_alpha15_code65() -> None:
    gradle = read('app/build.gradle.kts')
    require('versionCode = 65' in gradle, 'Alpha15 must use versionCode 65')
    require('versionName = "2.0.0-alpha15"' in gradle, 'Alpha15 versionName missing')
    require(read('VERSION').strip() == '2.0.0-alpha15', 'VERSION file must be Alpha15')


def test_document_and_translation_stay_inside_scanner_pro_camera() -> None:
    scan = read('app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt')
    require('SmartScanAction.DOCUMENT -> launchMode(ScanCaptureMode.DOCUMENT)' in scan,
            'single document still leaves the in-app camera flow')
    require('SmartScanAction.TRANSLATION -> launchMode(ScanCaptureMode.TRANSLATION)' in scan,
            'translation still leaves the in-app camera flow')
    require('launchProfessionalSinglePageScanner(selectedMode, index)' not in scan,
            'retake still routes through the external professional scanner')


def test_save_result_has_dedicated_success_state() -> None:
    scan = read('app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt')
    require('saveSuccessFile' in scan, 'dedicated save success state missing')
    require('ScanSaveSuccessContent(' in scan, 'dedicated save success UI missing')
    require('onOpenSaved' in scan and 'onShareSaved' in scan and 'onNewScan' in scan,
            'save success actions must include open/share/new scan')
    require('saveSuccessFile = saved' in scan, 'successful PDF save must open success UI')


def test_pdf_is_verified_before_review_pages_are_destroyed() -> None:
    core = read('app/src/main/java/com/abfilepro/app/core/ScanProcessor.kt')
    require('require(out.exists() && out.length() > 0L)' in core,
            'PDF output must be verified before save is reported as successful')
    scan = read('app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt')
    require(
        'require(translated.file.exists() && translated.file.length() > 0L) { "scan_save_output_invalid" }\n                    ScanProcessor.cleanup(pagesSnapshot)' in scan,
        'translation draft pages must survive until translated output is verified'
    )
    require(
        'require(saved.exists() && saved.length() > 0L) { "scan_save_output_invalid" }\n                    ScanProcessor.cleanup(pagesSnapshot)' in scan,
        'PDF draft pages must survive until final output is verified'
    )


def test_protected_files_unchanged() -> None:
    for rel, digest in PROTECTED.items():
        require(sha(rel) == digest, f'protected file changed: {rel}')


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
    print(f'ALPHA15_SAVE_FLOW_CONTRACT: {passed}/{len(tests)} PASS')
