from pathlib import Path

root = Path(__file__).resolve().parents[1]
screen = (root / 'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantModuleScreens.kt').read_text()
presentation = (root / 'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantPresentation.kt').read_text()
service = (root / 'app/src/main/java/com/abfilepro/app/assistant/ProductPriceComparisonService.kt').read_text()
alerts = (root / 'app/src/main/java/com/abfilepro/app/assistant/ProductPriceAlerts.kt').read_text()
gradle = (root / 'app/build.gradle.kts').read_text()
version = (root / 'VERSION').read_text().strip()

checks = {
    'version_name': version == '2.0.0-alpha96-price-comparison-classic-ui',
    'version_code': 'versionCode = 157' in gradle,
    'module_title': 'copy("مقارنة الأسعار", "Price comparison"' in presentation,
    'classic_search_card': 'ابحث باسم المنتج' in screen and 'Color(0xFFFFF1F3)' in screen,
    'classic_product_card': 'Color(0xFFECFFF8)' in screen and 'تغيير المنتج' in screen,
    'classic_sort_bar': 'الأقل سعرًا' in screen and 'الأسرع توصيلًا' in screen and 'Color(0xFFFA3443)' in screen,
    'classic_store_cards': 'ClassicPriceStoreResultCard' in screen and 'فتح المتجر' in screen,
    'classic_alert_button': 'إنشاء تنبيه سعر' in screen and 'Color(0xFFFA233A)' in screen,
    'three_stores_live': all(x in service for x in ['AMAZON("أمازون مصر"', 'NOON("نون"', 'JUMIA("جوميا"']),
    'no_fake_prices': 'priceEgp = null' in service and 'price_not_exposed' in service,
    'voice_search_preserved': 'RecognizerIntent.ACTION_RECOGNIZE_SPEECH' in screen,
    'barcode_preserved': 'BarcodeCamera(' in screen,
    'alerts_preserved': 'class ProductPriceAlertWorker' in alerts,
    'store_open_preserved': 'openWeb(context, result.productUrl)' in screen,
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(f"[{'PASS' if ok else 'FAIL'}] {name}")
if failed:
    raise SystemExit('Failed: ' + ', '.join(failed))
print(f"Alpha96 contract: {len(checks)}/{len(checks)} PASS")
