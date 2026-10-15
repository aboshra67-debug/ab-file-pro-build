from pathlib import Path
from PIL import Image, ImageStat

root = Path(__file__).resolve().parents[2]
home_path = root / 'app/src/main/java/com/abfilepro/app/ui/screens/HomeScreen.kt'
build_path = root / 'app/build.gradle.kts'
asset_path = root / 'app/src/main/res/drawable-nodpi/ab_file_pro_home_ar.webp'

home = home_path.read_text(encoding='utf-8')
build = build_path.read_text(encoding='utf-8')

# Alpha08: artwork must live inside safe system bars and fill the usable screen.
assert 'windowInsetsPadding(WindowInsets.safeDrawing)' in home
assert 'BoxWithConstraints(Modifier.fillMaxSize())' in home
assert 'val artworkHeight = maxHeight' in home
assert 'ContentScale.FillBounds' in home
assert '.verticalScroll(scrollState)' not in home

# Assistant and Convert visible cards/actions are swapped: convert left, assistant right.
convert_pos = home.index('label = "تحويل الملفات"')
assistant_pos = home.index('label = "المساعد الشخصي"')
assert convert_pos < assistant_pos

# Visual asset itself must match that swap (left lower card purple-ish, right lower card green-ish).
im = Image.open(asset_path).convert('RGB')
w, h = im.size
left = im.crop((int(w * .04), int(h * .59), int(w * .48), int(h * .78)))
right = im.crop((int(w * .51), int(h * .59), int(w * .95), int(h * .78)))
left_mean = ImageStat.Stat(left).mean
right_mean = ImageStat.Stat(right).mean
# Convert card is lilac/purple: blue and red should beat green in the average.
assert left_mean[2] > left_mean[1] and left_mean[0] > left_mean[1], left_mean
# Assistant card is mint/green: green should beat red in the average.
assert right_mean[1] > right_mean[0], right_mean

assert 'versionCode = 58' in build
assert 'versionName = "2.0.0-alpha08"' in build

print('alpha08 home fit + assistant/convert swap contract: PASS')
