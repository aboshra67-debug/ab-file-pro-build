from pathlib import Path
root=Path(__file__).resolve().parents[1]
ui=(root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantModuleScreens.kt').read_text()
svc=(root/'app/src/main/java/com/abfilepro/app/assistant/CurrencyRateService.kt').read_text()
gradle=(root/'app/build.gradle.kts').read_text()
version=(root/'VERSION').read_text().strip()
checks={
 'identity': version=='2.0.0-alpha70-calculator-live-currency' and 'versionCode = 131' in gradle and 'versionName = "2.0.0-alpha70-calculator-live-currency"' in gradle,
 'same_calculator_screen': 'CalculatorModuleScreen' in ui and 'mode == "currency"' in ui and 'mode == "calculator"' in ui,
 'currency_pairs': all(x in ui for x in ['EGP','USD','EUR','SAR','AED','GBP','KWD','QAR']),
 'swap_refresh': 'Icons.Default.SwapHoriz' in ui and 'Icons.Default.Refresh' in ui,
 'offline_cache': 'getSharedPreferences' in svc and 'fromCache' in svc and 'CACHE_MAX_AGE_MS' in svc,
 'network_endpoint': 'https://open.er-api.com/v6/latest/USD' in svc,
 'safe_https': 'HttpURLConnection' in svc and 'https://' in svc,
 'conversion_math': 'amount / fromRate * toRate' in svc,
 'source_update_time': 'time_last_update_unix' in svc and 'providerUpdatedAtMillis' in ui,
 'attribution': 'Rates by ExchangeRate-API' in ui and 'https://www.exchangerate-api.com' in ui,
 'arabic_digits': "'٠' -> '0'" in ui and "'٩' -> '9'" in ui,
}
failed=[k for k,v in checks.items() if not v]
for k,v in checks.items(): print(('[PASS] ' if v else '[FAIL] ')+k)
if failed: raise SystemExit('Alpha70 contract failed: '+', '.join(failed))
print('Alpha70 Calculator Live Currency contract: PASS')
