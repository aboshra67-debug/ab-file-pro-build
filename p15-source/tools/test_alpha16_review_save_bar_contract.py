from pathlib import Path

root = Path(__file__).resolve().parents[1]
scan = (root / 'app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt').read_text(encoding='utf-8')
build = (root / 'app/build.gradle.kts').read_text(encoding='utf-8')
version = (root / 'VERSION').read_text(encoding='utf-8').strip()

checks = {
    'alpha16_version': 'versionCode = 66' in build and 'versionName = "2.0.0-alpha16"' in build and version == '2.0.0-alpha16',
    'review_has_sticky_bottom_bar': 'private fun ScanReviewSaveBar(' in scan and 'bottomBar = {' in scan and 'ScanReviewSaveBar(' in scan,
    'save_button_is_not_only_lazy_content': 'Text(if (busy) context.uiText(language, "scan_screen_035") else context.uiText(language, primaryActionKey))' in scan,
    'review_list_has_bottom_clearance': 'contentPadding = PaddingValues(bottom = 12.dp)' in scan,
}

for name, ok in checks.items():
    print(f'{name}: {"PASS" if ok else "FAIL"}')

failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit('FAILED: ' + ', '.join(failed))
print('ALPHA16_REVIEW_SAVE_BAR_CONTRACT: PASS')
