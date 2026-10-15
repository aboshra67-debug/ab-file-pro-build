from pathlib import Path
import hashlib

ROOT = Path(__file__).resolve().parents[1]
SCAN = ROOT / 'app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt'
BUILD = ROOT / 'app/build.gradle.kts'
VERSION = ROOT / 'VERSION'

PROTECTED = {
    'app/src/main/java/com/abfilepro/app/MainActivity.kt': '0cd0d76e4de33fb3ea18542e70cbc28247375a1baf40afa8bd3656dbf5f69517',
    'app/src/main/java/com/abfilepro/app/core/FileUtils.kt': '7996c31f5228a63396056deeaa78ba53a65cfba469c3ad42ea2c4220c20e5add',
    'app/src/main/java/com/abfilepro/app/ui/screens/FilesScreen.kt': 'ea97c1c957a21201328668a6342fc1950a9121470d363408bb925e2a0424b6d4',
}

scan = SCAN.read_text(encoding='utf-8')
build = BUILD.read_text(encoding='utf-8')
version = VERSION.read_text(encoding='utf-8').strip()

checks = {
    'alpha17_version': 'versionCode = 67' in build and 'versionName = "2.0.0-alpha17"' in build and version == '2.0.0-alpha17',
    'single_authoritative_flow_stage': 'private enum class ScanUiStage' in scan and 'var flowStage by remember { mutableStateOf(ScanUiStage.HUB) }' in scan,
    'legacy_camera_boolean_removed': 'cameraOpen' not in scan,
    'capture_enters_review_explicitly': 'flowStage = ScanUiStage.REVIEW' in scan and 'onPagesCaptured = { captured ->' in scan,
    'hub_launch_enters_camera': 'flowStage = ScanUiStage.CAMERA' in scan and 'fun launchMode(mode: ScanCaptureMode)' in scan,
    'saving_has_explicit_stage': 'flowStage = ScanUiStage.SAVING' in scan,
    'success_has_explicit_stage': 'flowStage = ScanUiStage.SUCCESS' in scan,
    'rendering_is_stage_driven': 'when (flowStage)' in scan and 'ScanUiStage.CAMERA ->' in scan and 'ScanUiStage.REVIEW, ScanUiStage.SAVING ->' in scan and 'ScanUiStage.HUB ->' in scan,
}

for rel, digest in PROTECTED.items():
    current = hashlib.sha256((ROOT / rel).read_bytes()).hexdigest()
    checks[f'protected:{rel}'] = current == digest

failed = []
for name, ok in checks.items():
    print(f'{name}: {"PASS" if ok else "FAIL"}')
    if not ok:
        failed.append(name)

if failed:
    raise SystemExit('FAILED: ' + ', '.join(failed))
print('ALPHA17_SCAN_FLOW_STAGE_CONTRACT: PASS')
