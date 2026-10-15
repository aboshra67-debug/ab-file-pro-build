"""Alpha98-on-Alpha98 non-destructive calculator/currency layout contract.
This is static source verification; it is NOT an Android UI/install test.
"""
from pathlib import Path
root = Path(__file__).resolve().parents[1]
calc = (root / 'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantReferenceVisuals.kt').read_text()
ui = (root / 'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantModuleScreens.kt').read_text()
rates = (root / 'app/src/main/java/com/abfilepro/app/assistant/CurrencyRateService.kt').read_text()
manifest = (root / 'app/src/main/AndroidManifest.xml').read_text()
version = (root / 'VERSION').read_text().strip()
gradle = (root / 'app/build.gradle.kts').read_text()
checks = {
    'alpha98_baseline_identity': version == '2.0.0-alpha98-prayer-ui-reference-polish' and 'versionCode = 159' in gradle,
    'calculator_same_route': 'if (mode == "calculator") {\n        OperationalReferenceCalculator(' in ui,
    'calculator_clear_all': 'CalculatorKey("مسح"' in calc and '::clearAll)' in calc,
    'calculator_backspace': 'CalculatorKey("⌫"' in calc and '::removeLastCharacter)' in calc and 'expression.dropLast(1)' in calc,
    'calculator_reset_preview_after_backspace': 'expression = expression.dropLast(1)\n        recomputePreview()' in calc,
    'calculator_percent_kept': 'CalculatorKey("%"' in calc and 'fun percent()' in calc,
    'calculator_divide_multiply_kept': 'CalculatorKey("÷"' in calc and 'CalculatorKey("×"' in calc,
    'calculator_history_kept': 'history = (listOf(CalculatorHistoryRow' in calc and 'مسح السجل' in calc,
    'calculator_copy_kept': 'fun copyResult()' in calc and 'نسخ النتيجة' in calc,
    'currency_vertical_scroll': 'verticalScroll(rememberScrollState())' in ui,
    'currency_same_screen': 'CurrencyConverterPanel(' in ui and 'onOpenCurrency = { mode = "currency" }' in ui,
    'currency_full_set': all(s in ui for s in ['"EGP", "USD", "EUR", "SAR", "AED", "GBP", "KWD", "QAR", "CNY", "JPY", "CHF", "CAD"']),
    'currency_unchanged_rates_service': all(s in rates for s in ['Dispatchers.IO', 'MODE_PRIVATE', 'CACHE_MAX_AGE_MS', 'https://api.exchangerate.fun/latest?base=USD', 'https://open.er-api.com/v6/latest/USD']),
    'currency_swap_kept': 'onSwap = { val old = fromCurrency; fromCurrency = toCurrency; toCurrency = old }' in ui,
    'currency_precision_kept': 'fun sanitizeCurrencyInput' in ui and 'fun formatCurrencyValue' in ui,
    'currency_refresh_kept': 'onRefresh = { refreshRates(true) }' in ui,
    'currency_explicit_freshness': 'آخر فحص من التطبيق' in ui and 'آخر سعر محفوظ على الجهاز' in ui,
    'currency_named_chips_multilingual': 'currencyNamesArabic' in ui and 'currencyNamesEnglish' in ui and 'currencyNamesFrench' in ui,
    'currency_two_clear_sections': 'من عملة' in ui and 'إلى عملة' in ui,
    'security_no_change_in_manifest': 'android:allowBackup="false"' in manifest and 'android:usesCleartextTraffic="false"' in manifest,
}
failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():print(f"[{'PASS' if ok else 'FAIL'}] {name}")
if failed: raise SystemExit('FAILED: '+', '.join(failed))
print(f"Alpha98 calculator/currency safe patch static contract: {len(checks)}/{len(checks)} PASS")
