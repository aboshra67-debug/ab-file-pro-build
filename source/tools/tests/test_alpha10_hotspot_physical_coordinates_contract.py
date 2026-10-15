from pathlib import Path

root = Path(__file__).resolve().parents[2]
home = (root / 'app/src/main/java/com/abfilepro/app/ui/screens/HomeScreen.kt').read_text(encoding='utf-8')

build = (root / 'app/build.gradle.kts').read_text(encoding='utf-8')
assert 'versionCode = 60' in build, 'Alpha10 must use versionCode 60'
assert 'versionName = "2.0.0-alpha10"' in build, 'Alpha10 must use versionName 2.0.0-alpha10'

# Reference artwork hotspot coordinates are authored in physical LTR image coordinates.
# The transparent hotspot layer must therefore force LTR placement even while the Arabic
# app UI itself remains RTL. absoluteOffset alone is not enough if the parent Box aligns
# children from TopStart under RTL.
assert 'LocalLayoutDirection' in home, 'hotspot overlay must control layout direction explicitly'
assert 'LayoutDirection.Ltr' in home, 'reference hotspot layer must use physical LTR coordinates'
assert 'CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr)' in home, \
    'reference hotspot layer must force LTR placement'

# Navigation callbacks must remain unchanged and distinct.
assert 'onFiles = { onNavigate("files") }' in home
assert 'onScanner = { onNavigate("scan") }' in home
assert 'onConvert = { onNavigate("convert") }' in home
assert 'onAssistant = { activeSheet = HomeSheet.ASSISTANT }' in home


# The four visible cards must keep their physical artwork positions and handlers.
for label, handler, bounds in [
    ('Scanner المسح الضوئي', 'onScanner', '.025f, .375f, .465f, .210f'),
    ('مدير الملفات', 'onFiles', '.505f, .375f, .470f, .210f'),
    ('تحويل الملفات', 'onConvert', '.025f, .585f, .465f, .205f'),
    ('المساعد الشخصي', 'onAssistant', '.505f, .585f, .470f, .205f'),
]:
    label_pos = home.index(f'label = "{label}"')
    handler_pos = home.index(f'onClick = {handler}', label_pos)
    bounds_pos = home.index(f'Modifier.referenceBounds(artworkWidth, artworkHeight, {bounds})', handler_pos)
    assert label_pos < handler_pos < bounds_pos

print('alpha10 physical hotspot coordinate contract: PASS')
