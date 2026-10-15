from pathlib import Path
root=Path(__file__).resolve().parents[1]
module=(root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantModuleScreens.kt').read_text()
ref=(root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantReferenceVisuals.kt').read_text()
svc=(root/'app/src/main/java/com/abfilepro/app/assistant/CurrencyRateService.kt').read_text()
build=(root/'app/build.gradle.kts').read_text(); version=(root/'VERSION').read_text().strip()
checks={
 'identity': version=='2.0.0-alpha73-calculator-layout-currency-tab' and 'versionCode = 134' in build,
 'direct_calc': 'OperationalReferenceCalculator(' in module and 'if (mode == "calculator")' in module,
 'visible_currency_tab': 'تحويل العملات' in ref and 'onOpenCurrency' in ref,
 'responsive_no_raster': 'painterResource(R.drawable.pa_ref_calculator)' not in ref[ref.index('internal fun OperationalReferenceCalculator'):ref.index('internal fun AssistantReferenceModuleScreen')],
 'western_digits': "replace(\"*\", \"×\")" in ref and "charArrayOf('٠'" not in ref,
 'layout': 'CalculatorKey("C"' in ref and 'CalculatorKey("=", Modifier.fillMaxWidth().height(152.dp)' in ref,
 'history_copy': 'مسح السجل' in ref and 'نسخ النتيجة' in ref and 'ClipboardManager' in ref,
 'currency_preserved': 'CurrencyConverterPanel(' in module and 'CurrencyRateService.load' in module,
 'hourly_provider': 'api.exchangerate.fun' in svc and 'open.er-api.com' in svc,
}
failed=[k for k,v in checks.items() if not v]
for k,v in checks.items(): print(('[PASS] ' if v else '[FAIL] ')+k)
if failed: raise SystemExit('Alpha73 contract failed: '+', '.join(failed))
print('Alpha73 calculator layout + currency tab contract: PASS')
