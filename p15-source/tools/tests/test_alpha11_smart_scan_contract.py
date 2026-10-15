from pathlib import Path
import hashlib

root = Path(__file__).resolve().parents[2]
read = lambda rel: (root / rel).read_text(encoding='utf-8')
sha = lambda rel: hashlib.sha256((root / rel).read_bytes()).hexdigest()

build = read('app/build.gradle.kts')
scan_core = read('app/src/main/java/com/abfilepro/app/core/ScanProcessor.kt')
scan_screen = read('app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt')
barcode = read('app/src/main/java/com/abfilepro/app/ui/screens/BarcodeScreen.kt')

assert 'versionCode = 61' in build
assert 'versionName = "2.0.0-alpha11"' in build
assert 'BATCH(10, ScanFilter.DOCUMENT)' in scan_core
assert 'PHOTO(10, ScanFilter.ORIGINAL' in scan_core
assert 'RECEIPT(1, ScanFilter.DOCUMENT)' in scan_core

hub_path = root / 'app/src/main/java/com/abfilepro/app/ui/screens/SmartScanHub.kt'
assert hub_path.exists()
hub = hub_path.read_text(encoding='utf-8')
for action in ['DOCUMENT', 'BATCH', 'ID_CARD', 'RECEIPT', 'BARCODE', 'TRANSLATION', 'IMAGES_TO_PDF']:
    assert action in hub
for resource in [
    'scan_mode_document.webp', 'scan_mode_batch.webp', 'scan_mode_id.webp',
    'scan_mode_receipt.webp', 'scan_mode_barcode.webp', 'scan_mode_translation.webp',
    'scan_mode_images_pdf.webp'
]:
    assert (root / 'app/src/main/res/drawable-nodpi' / resource).exists(), resource

assert 'SmartScanHub(' in scan_screen
assert 'readerOnly = true' in scan_screen
assert 'ScanCaptureMode.PHOTO' in scan_screen
assert 'CardScanProcessor.saveCombinedPdf' in scan_screen
assert 'TranslationProcessor' in scan_screen
assert 'lastSavedFile' in scan_screen
assert 'FileUtils.shareFile' in scan_screen
assert 'readerOnly: Boolean = false' in barcode
assert 'Intent.ACTION_VIEW' in barcode

translation = root / 'app/src/main/java/com/abfilepro/app/core/TranslationProcessor.kt'
translation_ui = root / 'app/src/main/java/com/abfilepro/app/ui/screens/ScanTranslationResult.kt'
assert translation.exists()
assert translation_ui.exists()
assert 'com.google.mlkit:language-id:17.0.6' in build
assert 'com.google.mlkit:translate:17.0.3' in build

expected = {
    'app/src/main/java/com/abfilepro/app/MainActivity.kt': '0cd0d76e4de33fb3ea18542e70cbc28247375a1baf40afa8bd3656dbf5f69517',
    'app/src/main/java/com/abfilepro/app/core/FileUtils.kt': '7996c31f5228a63396056deeaa78ba53a65cfba469c3ad42ea2c4220c20e5add',
    'app/src/main/java/com/abfilepro/app/ui/screens/FilesScreen.kt': 'ea97c1c957a21201328668a6342fc1950a9121470d363408bb925e2a0424b6d4',
}
for rel, digest in expected.items():
    assert sha(rel) == digest, f'protected file changed: {rel}'

print('alpha11 smart scan contract: PASS')
