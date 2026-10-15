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


def test_version_alpha14_code64() -> None:
    gradle = read('app/build.gradle.kts')
    require('versionCode = 64' in gradle, 'Alpha14 must use versionCode 64')
    require('versionName = "2.0.0-alpha14"' in gradle, 'Alpha14 versionName missing')
    require(read('VERSION').strip() == '2.0.0-alpha14', 'VERSION file must be Alpha14')


def test_scanner_import_does_not_silently_drop_all_pages() -> None:
    core = read('app/src/main/java/com/abfilepro/app/core/ScanProcessor.kt')
    require('copyScannerUriToFile' in core, 'scanner URI importer helper missing')
    require('openFileDescriptor' in core, 'file-descriptor fallback missing')
    require('failedImports' in core, 'failed import evidence must be tracked')
    require('scan_page_import_failed' in core, 'all-page import failure must be explicit')
    require('failed imports are simply omitted' not in core, 'old silent import failure behavior still present')


def test_document_and_translation_use_professional_single_page_scanner() -> None:
    scan = read('app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt')
    require('launchProfessionalSinglePageScanner' in scan, 'shared professional single-page launcher missing')
    require('SmartScanAction.DOCUMENT -> launchProfessionalSinglePageScanner(ScanCaptureMode.DOCUMENT)' in scan,
            'document does not use professional scanner')
    require('SmartScanAction.TRANSLATION -> launchProfessionalSinglePageScanner(ScanCaptureMode.TRANSLATION)' in scan,
            'translation still does not use professional scanner')
    require('professionalScanMode.defaultFilter' in scan, 'professional scanner result must preserve requested mode')


def test_translation_retake_stays_on_professional_scanner() -> None:
    scan = read('app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt')
    require('selectedMode == ScanCaptureMode.DOCUMENT || selectedMode == ScanCaptureMode.TRANSLATION' in scan,
            'translation retake must use professional scanner too')
    require('launchProfessionalSinglePageScanner(selectedMode, index)' in scan,
            'retake must preserve document/translation mode')


def test_ocr_models_are_bundled_and_no_runtime_github_dependency() -> None:
    ocr = read('app/src/main/java/com/abfilepro/app/core/OcrProcessor.kt')
    require('assets.open("tessdata/$code.traineddata")' in ocr, 'OCR model must be copied from app assets')
    require('raw.githubusercontent.com/tesseract-ocr' not in ocr, 'runtime GitHub model dependency still present')
    require('HttpURLConnection' not in ocr and 'java.net.URL' not in ocr, 'network model downloader still present')
    for code in ('ara', 'eng', 'fra'):
        model = ROOT / f'app/src/main/assets/tessdata/{code}.traineddata'
        require(model.exists(), f'bundled {code} model missing')
        require(model.stat().st_size > 100_000, f'bundled {code} model is too small')


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
    print(f'ALPHA14_REVIEW_TRANSLATION_CONTRACT: {passed}/{len(tests)} PASS')
