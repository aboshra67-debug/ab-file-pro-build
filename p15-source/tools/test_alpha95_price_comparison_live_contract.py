from pathlib import Path

root = Path(__file__).resolve().parents[1]
screen = (root / 'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantModuleScreens.kt').read_text()
presentation = (root / 'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantPresentation.kt').read_text()
service = (root / 'app/src/main/java/com/abfilepro/app/assistant/ProductPriceComparisonService.kt').read_text()
alerts = (root / 'app/src/main/java/com/abfilepro/app/assistant/ProductPriceAlerts.kt').read_text()
barcode = (root / 'app/src/main/java/com/abfilepro/app/ui/screens/BarcodeScreen.kt').read_text()
gradle = (root / 'app/build.gradle.kts').read_text()
version = (root / 'VERSION').read_text().strip()

checks = {
    'version_name': version == '2.0.0-alpha95-price-comparison-live',
    'version_code': 'versionCode = 156' in gradle,
    'direct_compare_dispatch': 'if (module == AssistantModule.COMPARE) {\n        PriceComparisonScreen(onBack)\n        return' in screen,
    'module_title': 'copy("مقارنة الأسعار", "Price comparison"' in presentation,
    'three_stores': all(x in service for x in ['AMAZON("أمازون مصر"', 'NOON("نون"', 'JUMIA("جوميا"']),
    'no_fake_price_fallback': 'priceEgp = null' in service and 'price_not_exposed' in service,
    'voice_search': 'RecognizerIntent.ACTION_RECOGNIZE_SPEECH' in screen,
    'barcode_scan': 'BarcodeCamera(' in screen and 'internal fun BarcodeCamera' in barcode,
    'price_alert_worker': 'class ProductPriceAlertWorker' in alerts and 'PeriodicWorkRequestBuilder<ProductPriceAlertWorker>' in alerts,
    'store_open_action': 'openWeb(context, result.productUrl)' in screen,
    'sort_lowest': 'PriceSortMode.CHEAPEST' in screen,
    'sort_delivery': 'PriceSortMode.FASTEST' in screen,
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(f"[{'PASS' if ok else 'FAIL'}] {name}")
if failed:
    raise SystemExit('Failed: ' + ', '.join(failed))
print(f"Alpha95 contract: {len(checks)}/{len(checks)} PASS")
