from pathlib import Path

root = Path(__file__).resolve().parents[2]
home = (root / 'app/src/main/java/com/abfilepro/app/ui/screens/HomeScreen.kt').read_text(encoding='utf-8')
build = (root / 'app/build.gradle.kts').read_text(encoding='utf-8')

# Arabic home runs under RTL. Hotspot X coordinates are authored against the physical artwork,
# so they must use absoluteOffset; relative offset mirrors X in RTL and swaps left/right actions.
ref_fn = home[home.index('private fun Modifier.referenceBounds'):]
assert '.absoluteOffset(' in ref_fn, 'referenceBounds must use absoluteOffset in RTL artwork'
assert '.offset(x = canvasWidth * x' not in ref_fn, 'relative offset mirrors hotspot X under RTL'

# Explicit scanner/files routes remain distinct and mapped to their visible cards.
assert 'label = "Scanner المسح الضوئي"' in home
assert 'onClick = onScanner' in home
assert 'label = "مدير الملفات"' in home
assert 'onClick = onFiles' in home
assert 'Modifier.referenceBounds(artworkWidth, artworkHeight, .025f, .375f, .465f, .210f)' in home
assert 'Modifier.referenceBounds(artworkWidth, artworkHeight, .505f, .375f, .470f, .210f)' in home

assert 'versionCode = 59' in build
assert 'versionName = "2.0.0-alpha09"' in build
print('alpha09 hotspot RTL contract: PASS')
