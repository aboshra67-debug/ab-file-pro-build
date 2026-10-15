from __future__ import annotations

from pathlib import Path
import hashlib
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PROTECTED_BASELINE_SHA256 = {
    'app/src/main/java/com/abfilepro/app/MainActivity.kt': '0cd0d76e4de33fb3ea18542e70cbc28247375a1baf40afa8bd3656dbf5f69517',
    'app/src/main/java/com/abfilepro/app/core/FileUtils.kt': '7996c31f5228a63396056deeaa78ba53a65cfba469c3ad42ea2c4220c20e5add',
    'app/src/main/java/com/abfilepro/app/ui/screens/FilesScreen.kt': 'ea97c1c957a21201328668a6342fc1950a9121470d363408bb925e2a0424b6d4',
}


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding='utf-8')


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def assert_contains(text: str, needle: str, label: str) -> None:
    assert needle in text, f'missing {label}: {needle}'


def test_identity_and_version() -> None:
    gradle = read('app/build.gradle.kts')
    assert_contains(gradle, 'applicationId = "com.abfilepro.app"', 'package')
    assert_contains(gradle, 'versionCode = 63', 'version code 62')
    assert_contains(gradle, 'versionName = "2.0.0-alpha13"', 'alpha12 version')
    assert read('VERSION').strip() == '2.0.0-alpha13'


def test_protected_files_unchanged() -> None:
    for rel, expected in PROTECTED_BASELINE_SHA256.items():
        assert sha256(ROOT / rel) == expected, f'protected file changed: {rel}'


def test_hub_is_compact_and_named() -> None:
    src = read('app/src/main/java/com/abfilepro/app/ui/screens/SmartScanHub.kt')
    assert 'heightIn(min = 248.dp)' not in src
    assert 'Spacer(Modifier.weight(1f))' not in src
    assert_contains(src, 'Modifier.height(218.dp)', 'compact normal card')
    assert_contains(src, 'Modifier.height(204.dp)', 'compact wide card')
    assert_contains(src, 'BorderStroke(1.dp', 'card border')
    assert_contains(src, 'maxLines = if (isWide) 1 else 2', 'subtitle bounds')


def test_internal_back_flow_is_explicit() -> None:
    rules = read('app/src/main/java/com/abfilepro/app/core/ScanInteractionRules.kt')
    scan = read('app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt')
    assert_contains(rules, 'ScanFlowStage.REVIEW -> ScanBackTarget.HUB', 'review to hub')
    assert_contains(rules, 'if (hasPages) ScanBackTarget.REVIEW else ScanBackTarget.HUB', 'camera back flow')
    assert_contains(scan, 'ScanInteractionRules.backTarget(ScanFlowStage.CAMERA, hasPages)', 'camera internal back use')
    assert_contains(scan, 'ScanInteractionRules.backTarget(ScanFlowStage.REVIEW, reviewPages.isNotEmpty())', 'review internal back use')
    assert_contains(scan, 'BackHandler { if (!busy) onBack() }', 'review system back protection')
    assert 'BackHandler(enabled = !busy)' not in scan


def test_camera_interactions_and_busy_guards() -> None:
    camera = read('app/src/main/java/com/abfilepro/app/ui/screens/ScannerProCamera.kt')
    for needle, label in [
        ('ScaleGestureDetector', 'pinch zoom'),
        ('FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE', 'tap AF/AE'),
        ('startFocusAndMetering', 'focus action'),
        ('setZoomRatio(next)', 'camera zoom application'),
        ('enableTorch(torchEnabled)', 'flash/torch'),
        ('BackHandler { if (!busy) onClose() }', 'camera safe back'),
        ('IconButton(onClick = onClose, enabled = !busy)', 'camera close busy guard'),
        ('enabled = permissionGranted && !busy && remainingCapacity() > 0', 'capture double-action guard'),
        ('alpha12_camera_gesture_hint', 'camera gesture hint'),
    ]:
        assert_contains(camera, needle, label)


def test_editor_is_full_screen_and_featured() -> None:
    scan = read('app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt')
    for needle, label in [
        ('DialogProperties(usePlatformDefaultWidth = false)', 'full-screen editor'),
        ('ScanEditorSection.FILTERS', 'filters tab'),
        ('ScanEditorSection.ADJUST', 'adjust tab'),
        ('ScanEditorSection.CROP', 'crop tab'),
        ('applyAutoEnhance()', 'auto enhance'),
        ('rotation -= 90', 'rotate left'),
        ('rotation += 90', 'rotate right'),
        ('PerspectiveQuadEditor', 'perspective editor'),
        ('AdjustmentSlider(context.uiText(language, "scan_screen_060")', 'brightness'),
        ('AdjustmentSlider(context.uiText(language, "scan_screen_061")', 'contrast'),
        ('AdjustmentSlider(context.uiText(language, "scan_screen_062")', 'sharpness'),
        ('onApply(draft)', 'save/apply'),
    ]:
        assert_contains(scan, needle, label)


def test_core_capture_limits_preserved() -> None:
    core = read('app/src/main/java/com/abfilepro/app/core/ScanProcessor.kt')
    expected = {
        'DOCUMENT': 1,
        'BATCH': 10,
        'ID_CARD': 2,
        'TRANSLATION': 1,
        'RECEIPT': 1,
        'PHOTO': 10,
    }
    for name, limit in expected.items():
        pattern = rf'\b{name}\({limit},'
        assert re.search(pattern, core), f'{name} max pages changed'


def test_alpha12_localization_complete_and_xml_valid() -> None:
    res = ROOT / 'app/src/main/res/values/strings_alpha12.xml'
    root = ET.parse(res).getroot()
    names = {node.attrib['name'] for node in root.findall('string')}
    base_keys = {
        'alpha12_editor_auto',
        'alpha12_editor_filters',
        'alpha12_editor_adjust',
        'alpha12_editor_crop',
        'alpha12_camera_gesture_hint',
    }
    for key in base_keys:
        for lang in ('ar', 'en', 'fr'):
            assert f'{key}_{lang}' in names, f'missing localized resource {key}_{lang}'


def test_all_xml_resources_parse() -> None:
    for path in (ROOT / 'app/src/main/res').rglob('*.xml'):
        ET.parse(path)


if __name__ == '__main__':
    tests = [obj for name, obj in sorted(globals().items()) if name.startswith('test_') and callable(obj)]
    for test in tests:
        test()
        print(f'PASS {test.__name__}')
    print(f'ALPHA13_SCAN_PATCH_CONTRACT: {len(tests)}/{len(tests)} PASS')
