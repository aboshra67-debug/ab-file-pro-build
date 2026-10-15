from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def text(rel):
    return (ROOT / rel).read_text(encoding='utf-8')

checks = []

def check(name, cond):
    checks.append((name, bool(cond)))

build = text('app/build.gradle.kts')
version = text('VERSION')
routes = text('app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantModuleScreens.kt')
screen = text('app/src/main/java/com/abfilepro/app/ui/screens/assistant/MarketsOperationalScreen.kt')
gold = text('app/src/main/java/com/abfilepro/app/assistant/GoldPriceService.kt')
alerts = text('app/src/main/java/com/abfilepro/app/assistant/MarketAlertStore.kt')
worker = text('app/src/main/java/com/abfilepro/app/assistant/MarketAlertWorker.kt')

check('version marker', version.strip() == '2.0.0-alpha75-markets-operational')
check('version code 136', 'versionCode = 136' in build)
check('version name', '2.0.0-alpha75-markets-operational' in build)
check('work manager dependency', 'androidx.work:work-runtime-ktx:2.10.0' in build)
check('direct markets route', 'if (module == AssistantModule.MARKETS)' in routes and 'MarketsOperationalScreen(onBack)' in routes)
check('reference artwork retained', 'R.drawable.pa_ref_currency' in screen)
check('manual refresh', 'force = true' in screen and 'Icons.Default.Refresh' in screen)
check('automatic refresh', '15L * 60L * 1000L' in screen)
check('currency pricing', 'marketPriceEgp(MarketAsset.USD' in screen and 'marketPriceEgp(MarketAsset.SAR' in screen)
check('gold 24/21/18', all(x in screen for x in ['MarketAsset.GOLD24', 'MarketAsset.GOLD21', 'MarketAsset.GOLD18']))
check('gold keyless endpoint', 'https://api.gold-api.com/price/XAU' in gold)
check('gold local cache', 'ab_file_pro_gold_price' in gold and 'KEY_JSON' in gold)
check('favorites persistence', 'favorites_v1' in alerts and 'saveFavorites' in alerts)
check('alerts persistence', 'alerts_v1' in alerts and 'MarketPriceAlert' in alerts)
check('periodic alert worker', 'PeriodicWorkRequestBuilder<MarketAlertWorker>(15, TimeUnit.MINUTES)' in worker)
check('notification permission safety', 'POST_NOTIFICATIONS' in worker and 'checkSelfPermission' in worker)
check('no fabricated spread comment', 'instead of inventing a bank spread' in screen)

failed = [name for name, ok in checks if not ok]
for name, ok in checks:
    print(f"[{'PASS' if ok else 'FAIL'}] {name}")
if failed:
    raise SystemExit('Alpha75 contract failed: ' + ', '.join(failed))
print(f'Alpha75 contract passed: {len(checks)}/{len(checks)}')
