from pathlib import Path
import hashlib

ROOT = Path(__file__).resolve().parents[1]
SCAN = (ROOT / 'app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt').read_text()
PROC = (ROOT / 'app/src/main/java/com/abfilepro/app/core/ScanProcessor.kt').read_text()
GRADLE = (ROOT / 'app/build.gradle.kts').read_text()
VERSION = (ROOT / 'VERSION').read_text().strip()
STRINGS = (ROOT / 'app/src/main/res/values/strings_alpha34.xml').read_text()

PROTECTED = {
    'app/src/main/java/com/abfilepro/app/MainActivity.kt': '0cd0d76e4de33fb3ea18542e70cbc28247375a1baf40afa8bd3656dbf5f69517',
        'app/src/main/java/com/abfilepro/app/ui/screens/FilesScreen.kt': 'ea97c1c957a21201328668a6342fc1950a9121470d363408bb925e2a0424b6d4',
}

def require(cond, msg):
    if not cond:
        raise AssertionError(msg)

def sha(path):
    return hashlib.sha256((ROOT / path).read_bytes()).hexdigest()

def test_identity():
    require('versionCode = 103' in GRADLE, 'Alpha37 versionCode must be 98')
    require('versionName = "2.0.0-alpha42-format-save"' in GRADLE, 'Alpha37 versionName mismatch')
    require(VERSION == '2.0.0-alpha42-format-save', 'Alpha37 VERSION mismatch')

def test_approved_editor_layout():
    require('alpha34_editor_subtitle' in SCAN, 'approved editor subtitle missing')
    require('alpha34_save_continue' in SCAN, 'fixed Save & Continue action missing')
    require('alpha34_reset_short' in SCAN, 'Reset action missing')
    require('primaryEditorFilters' in SCAN and 'EditorFilterPreview(' in SCAN, 'visual filter thumbnails missing')
    require('EditorToolButton(' in SCAN and 'EditorSectionButton(' in SCAN, 'approved tool organization missing')
    require('Modifier.weight(1.55f).height(58.dp)' in SCAN, 'primary save action must be large and prominent')

def test_save_bar_is_above_system_navigation():
    require('.navigationBarsPadding()' in SCAN, 'system navigation inset protection missing')
    require('.padding(bottom = 18.dp)' in SCAN, 'extra safe bottom spacing missing')
    require('bottomBar = {' in SCAN, 'save actions must be fixed in Scaffold bottomBar')

def test_existing_editor_features_preserved():
    require('fun applySmartClarity()' in SCAN, 'Smart Clarity must remain')
    require('fun applyAutoEnhance()' in SCAN, 'Auto Enhance must remain')
    require('fun undo()' in SCAN and 'fun redo()' in SCAN, 'undo/redo must remain')
    require('ScanEditorSection.CROP' in SCAN and 'PerspectiveQuadEditor(' in SCAN, 'crop/perspective tools must remain')
    require('ScanEditorSection.TEXT' in SCAN and 'ScanTextAnnotation' in SCAN, 'text annotation tools must remain')
    require('suggestSmartClarity' in PROC, 'Smart Clarity processor must remain')

def test_protected_files_unchanged():
    for path, expected in PROTECTED.items():
        require(sha(path) == expected, f'protected file changed: {path}')

for test in [
    test_identity,
    test_approved_editor_layout,
    test_save_bar_is_above_system_navigation,
    test_existing_editor_features_preserved,
    test_protected_files_unchanged,
]:
    test()
    print('PASS', test.__name__)
