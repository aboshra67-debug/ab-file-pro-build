from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def text(rel):
    return (ROOT / rel).read_text(encoding="utf-8")

checks = []
def check(name, cond): checks.append((name, bool(cond)))

build = text("app/build.gradle.kts")
version = text("VERSION").strip()
routes = text("app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantModuleScreens.kt")
screen = text("app/src/main/java/com/abfilepro/app/ui/screens/assistant/MarketsOperationalScreen.kt")
alerts = text("app/src/main/java/com/abfilepro/app/assistant/MarketAlertStore.kt")

check("version marker", version == "2.0.0-alpha77-markets-native-rebuild")
check("version code", "versionCode = 138" in build)
check("version name", "2.0.0-alpha77-markets-native-rebuild" in build)
check("direct markets route", "if (module == AssistantModule.MARKETS)" in routes and "MarketsOperationalScreen(onBack)" in routes)
check("no full page reference image", "R.drawable.pa_ref_currency" not in screen)
check("native currency cards", "CurrencyCard(" in screen and "الدولار الأمريكي" in screen and "الريال السعودي" in screen)
check("native gold cards", "GoldCard(" in screen and "عيار 24" in screen and "عيار 21" in screen and "عيار 18" in screen)
check("native segmented tabs", "SegmentedTabs(" in screen and "العملات" in screen and "الذهب" in screen)
check("manual refresh", "force = true" in screen and "Icons.Default.Refresh" in screen)
check("automatic refresh", "15L * 60L * 1000L" in screen)
check("currency prices", "marketPriceEgp(MarketAsset.USD" in screen and "marketPriceEgp(MarketAsset.SAR" in screen)
check("gold prices", all(x in screen for x in ["MarketAsset.GOLD24", "MarketAsset.GOLD21", "MarketAsset.GOLD18"]))
check("favorites", "MarketAlertStore.favorites" in screen and "saveFavorites" in screen and "favorites_v1" in alerts)
check("alerts", "MarketAlertDialog(" in screen and "MarketAlertStore.upsert" in screen and "alerts_v1" in alerts)
check("status bar safe area", ".statusBarsPadding()" in screen)
check("no reference geometry overlay", "absoluteOffset(x = sx * x" not in screen and "BoxWithConstraints" not in screen)

failed = [name for name, ok in checks if not ok]
for name, ok in checks:
    print(f"[{'PASS' if ok else 'FAIL'}] {name}")
if failed:
    raise SystemExit("Alpha77 contract failed: " + ", ".join(failed))
print(f"Alpha77 native markets contract passed: {len(checks)}/{len(checks)}")
