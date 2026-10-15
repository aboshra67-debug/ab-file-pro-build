from pathlib import Path
root = Path(__file__).resolve().parents[2]
home = (root / "app/src/main/java/com/abfilepro/app/ui/screens/HomeScreen.kt").read_text(encoding="utf-8")
strings = (root / "app/src/main/res/values/strings_v205_premium_home.xml").read_text(encoding="utf-8")
assert '"scan",' in home
assert 'context.uiText(language, "home_scanner")' in home
assert '<string name="home_scanner_ar">Scanner | المسح الضوئي</string>' in strings
assert '<string name="home_scanner_en">Scanner | Document Scan</string>' in strings
assert '<string name="home_scanner_fr">Scanner | Numérisation</string>' in strings
print("home scanner entry contract: PASS")
