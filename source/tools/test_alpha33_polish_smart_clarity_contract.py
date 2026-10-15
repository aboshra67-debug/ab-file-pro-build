from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCAN = (ROOT / "app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt").read_text()
PROC = (ROOT / "app/src/main/java/com/abfilepro/app/core/ScanProcessor.kt").read_text()
GRADLE = (ROOT / "app/build.gradle.kts").read_text()
STRINGS = (ROOT / "app/src/main/res/values/strings_alpha33.xml").read_text()

def require(cond, msg):
    if not cond:
        raise AssertionError(msg)

def test_identity():
    require('versionCode = 94' in GRADLE, 'Alpha33 versionCode must be 94')
    require('versionName = "2.0.0-alpha33-polish-smart-clarity"' in GRADLE, 'Alpha33 versionName mismatch')

def test_safe_bottom_actions():
    require(SCAN.count('navigationBarsPadding()') >= 3, 'save/editor actions must respect navigation bar safe area')
    require('Modifier.fillMaxWidth().height(54.dp)' in SCAN, 'primary save action must stay full-width and tappable')

def test_smart_clarity_option():
    require('fun suggestSmartClarity(page: ScanDraftPage, preview: Bitmap): ScanDraftPage' in PROC, 'smart clarity analyzer missing')
    require('fun applySmartClarity()' in SCAN, 'editor smart clarity action missing')
    require('alpha33_smart_clarity' in SCAN and 'وضوح ذكي' in STRINGS, 'smart clarity UI label missing')
    require('suggestedBrightness' in PROC and 'suggestedContrast' in PROC and 'suggestedSharpness' in PROC, 'smart clarity must adapt multiple image parameters')

def test_existing_flow_preserved():
    require('ScanUiStage.EDITOR' in SCAN, 'direct editor stage must remain')
    require('ScanProcessor.saveSinglePageJpeg' in SCAN and 'ScanProcessor.createPdf' in SCAN, 'existing JPG/PDF save paths must remain')
    require('FileUtils.shareFile(context, file)' in SCAN, 'generic sharing must remain')

for test in [test_identity, test_safe_bottom_actions, test_smart_clarity_option, test_existing_flow_preserved]:
    test()
    print('PASS', test.__name__)
