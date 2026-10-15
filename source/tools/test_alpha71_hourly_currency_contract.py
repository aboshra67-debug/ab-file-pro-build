from pathlib import Path
root=Path(__file__).resolve().parents[1]
ui=(root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantModuleScreens.kt').read_text()
svc=(root/'app/src/main/java/com/abfilepro/app/assistant/CurrencyRateService.kt').read_text()
main=(root/'app/src/main/java/com/abfilepro/app/MainActivity.kt').read_text()
files=(root/'app/src/main/java/com/abfilepro/app/ui/screens/FilesScreen.kt').read_text()
fileutils=(root/'app/src/main/java/com/abfilepro/app/core/FileUtils.kt').read_text()
gradle=(root/'app/build.gradle.kts').read_text()
version=(root/'VERSION').read_text().strip()
checks={
 'identity': version=='2.0.0-alpha71-hourly-currency' and 'versionCode = 132' in gradle and 'versionName = "2.0.0-alpha71-hourly-currency"' in gradle,
 'calculator_layout_preserved': 'val keys = listOf("7", "8", "9", "/", "4", "5", "6", "*", "1", "2", "3", "-", "0", ".", "(", ")", "C", "%", "⌫", "+")' in ui and 'Color(0xFF173B65)' in ui,
 'hourly_primary': 'https://api.exchangerate.fun/latest?base=USD' in svc and 'ExchangeRate.fun' in svc,
 'backup_source': 'https://open.er-api.com/v6/latest/USD' in svc and 'usedFallbackProvider' in svc,
 'fast_recheck': '15L * 60L * 1000L' in svc,
 'offline_cache': 'getSharedPreferences' in svc and 'fromCache' in svc and 'KEY_PROVIDER' in svc,
 'safe_https': 'HttpURLConnection' in svc and svc.count('https://') >= 2,
 'conversion_math': 'amount / fromRate * toRate' in svc,
 'freshness_ui': 'تحديث كل ساعة' in ui and 'آخر فحص من التطبيق' in ui and 'Hourly rates by ExchangeRate.fun' in ui,
 'currency_tab_preserved': 'mode == "currency"' in ui and 'Icons.Default.SwapHoriz' in ui and 'Icons.Default.Refresh' in ui,
 'protected_files_present': 'class MainActivity' in main and 'FilesScreen' in files and 'object FileUtils' in fileutils,
}
failed=[k for k,v in checks.items() if not v]
for k,v in checks.items(): print(('[PASS] ' if v else '[FAIL] ')+k)
if failed: raise SystemExit('Alpha71 contract failed: '+', '.join(failed))
print('Alpha71 Hourly Currency contract: PASS')
