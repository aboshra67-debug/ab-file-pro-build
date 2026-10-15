from pathlib import Path

root = Path(__file__).resolve().parents[2]
scan = (root / 'app/src/main/java/com/abfilepro/app/core/ScanProcessor.kt').read_text(encoding='utf-8')
i18n = (root / 'app/src/main/java/com/abfilepro/app/ui/i18n/LocalizedText.kt').read_text(encoding='utf-8')
ui = (root / 'app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt').read_text(encoding='utf-8')
strings = (root / 'app/src/main/res/values/strings_v141_b1.xml').read_text(encoding='utf-8')

assert 'DUPLEX(2, ScanFilter.DOCUMENT)' in scan
assert 'TRANSLATION(1, ScanFilter.DOCUMENT' in scan
assert 'ScanCaptureMode.DUPLEX -> "scan_mode_duplex"' in i18n
assert 'ScanCaptureMode.TRANSLATION -> "scan_mode_translation"' in i18n
assert 'ScannerModeCard' in ui
assert 'scanner_stage1_title_ar' in strings
assert 'scanner_stage1_translation_desc_ar' in strings
print('scanner stage1 contract: PASS')
