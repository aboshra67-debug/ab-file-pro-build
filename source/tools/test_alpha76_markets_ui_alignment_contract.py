from pathlib import Path

root = Path(__file__).resolve().parents[1]
screen = (root / 'app/src/main/java/com/abfilepro/app/ui/screens/assistant/MarketsOperationalScreen.kt').read_text()
build = (root / 'app/build.gradle.kts').read_text()
version = (root / 'VERSION').read_text().strip()

checks = {
    'version_name': '2.0.0-alpha76-markets-ui-alignment' in build and version == '2.0.0-alpha76-markets-ui-alignment',
    'version_code': 'versionCode = 137' in build,
    'absolute_coordinates': 'absoluteOffset(x = sx * x, y = sy * y)' in screen,
    'no_relative_offset_import': 'import androidx.compose.foundation.layout.offset' not in screen,
    'status_bar_safe_area': '.statusBarsPadding()' in screen,
    'reference_preserved': 'R.drawable.pa_ref_currency' in screen,
    'currency_service_preserved': 'CurrencyRateService.load' in screen,
    'gold_service_preserved': 'GoldPriceService.load' in screen,
    'alerts_preserved': 'MarketAlertDialog(' in screen and 'MarketAlertStore' in screen,
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(f'[{"PASS" if ok else "FAIL"}] {name}')
if failed:
    raise SystemExit('Alpha76 contract failed: ' + ', '.join(failed))
print(f'Alpha76 markets UI alignment contract: {len(checks)}/{len(checks)} PASS')
