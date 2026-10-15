from pathlib import Path

root = Path(__file__).resolve().parents[2]
home_path = root / 'app/src/main/java/com/abfilepro/app/ui/screens/HomeScreen.kt'
build_path = root / 'app/build.gradle.kts'
asset_path = root / 'app/src/main/res/drawable-nodpi/ab_file_pro_home_ar.webp'

home = home_path.read_text(encoding='utf-8')
build = build_path.read_text(encoding='utf-8')

# Alpha07 must render the approved Arabic reference artwork directly in HomeScreen.
assert 'ReferenceArtworkHome' in home
assert 'R.drawable.ab_file_pro_home_ar' in home
assert 'ContentScale.FillWidth' in home
assert 'BoxWithConstraints' in home
assert 'ReferenceHotspot' in home

# Hotspots must keep all visible destinations functional without editing protected screens.
for needle in [
    'onNavigate("files")',
    'onNavigate("scan")',
    'onNavigate("convert")',
    'activeSheet = HomeSheet.ASSISTANT',
    'activeSheet = HomeSheet.AI',
    'activeSheet = HomeSheet.HELP',
    'onNavigate("settings")',
    'languageDialog = true',
]:
    assert needle in home, needle

# Keep the artwork opt-in to Arabic so English/French retain live localized text.
assert 'language == AppLanguage.ARABIC && showRobot' in home

# Version bump for the visual patch.
assert 'versionCode = 57' in build
assert 'versionName = "2.0.0-alpha07"' in build

assert asset_path.exists(), asset_path
assert asset_path.stat().st_size < 900_000, asset_path.stat().st_size

print('alpha07 reference home contract: PASS')
