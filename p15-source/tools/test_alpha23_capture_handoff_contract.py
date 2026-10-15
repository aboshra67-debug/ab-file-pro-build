from pathlib import Path
import hashlib

ROOT = Path(__file__).resolve().parents[1]
SCAN = ROOT / 'app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt'
CAM = ROOT / 'app/src/main/java/com/abfilepro/app/ui/screens/ScannerProCamera.kt'
GRADLE = ROOT / 'app/build.gradle.kts'

scan = SCAN.read_text(encoding='utf-8')
cam = CAM.read_text(encoding='utf-8')
gradle = GRADLE.read_text(encoding='utf-8')
version = (ROOT / 'VERSION').read_text(encoding='utf-8').strip()

def require(cond, msg):
    if not cond:
        raise AssertionError(msg)

def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

def test_identity():
    require('versionCode = 73' in gradle, 'versionCode must be 73')
    require('versionName = "2.0.0-alpha23"' in gradle, 'versionName must be alpha23')
    require(version == '2.0.0-alpha23', 'VERSION mismatch')

def test_explicit_processing_state():
    require('private enum class ScanUiStage { HUB, CAMERA, PROCESSING, BOUNDS, REVIEW, SAVING, SUCCESS }' in scan,
            'PROCESSING state missing')
    fn = scan[scan.index('fun openProcessedSingleDocumentReview'):scan.index('fun leaveBoundsForCamera')]
    require('documentProcessingBusy = true' in fn, 'processing busy flag missing')
    require('flowStage = ScanUiStage.PROCESSING' in fn, 'capture must enter PROCESSING')
    before_launch = fn[:fn.index('scope.launch')]
    require('flowStage = ScanUiStage.REVIEW' not in before_launch,
            'REVIEW must not be exposed before processing completes')
    require('flowStage = ScanUiStage.REVIEW' in fn[fn.index('scope.launch'):],
            'processing completion must advance to REVIEW')

def test_processing_consumes_transient_back():
    block_start = scan.index('ScanUiStage.PROCESSING ->')
    block_end = scan.index('ScanUiStage.BOUNDS ->', block_start)
    block = scan[block_start:block_end]
    require('BackHandler(enabled = true)' in block, 'PROCESSING must consume transient Back')
    require('flowStage = ScanUiStage.HUB' not in block, 'PROCESSING must never route to HUB')

def test_camera_handoff_stays_busy_for_document():
    saved = cam[cam.index('override fun onImageSaved'):cam.index('override fun onError')]
    doc_gate = 'mode == ScanCaptureMode.DOCUMENT && accepted.isNotEmpty()'
    require(doc_gate in saved, 'DOCUMENT hand-off gate missing')
    gate_pos = saved.index(doc_gate)
    callback_pos = saved.index('onPagesCaptured(accepted)', gate_pos)
    return_pos = saved.index('return', callback_pos)
    busy_false_pos = saved.index('busy = false')
    require(callback_pos < return_pos < busy_false_pos,
            'DOCUMENT must hand off before Camera BackHandler is re-enabled')

def test_processing_close_guard():
    camera_block = scan[scan.index('ScanUiStage.CAMERA ->'):scan.index('ScanUiStage.PROCESSING ->')]
    close_block = camera_block[camera_block.index('onClose = {'):]
    require('selectedMode == ScanCaptureMode.DOCUMENT && documentProcessingBusy' in close_block,
            'queued camera close/back guard missing')
    guarded = close_block[close_block.index('selectedMode == ScanCaptureMode.DOCUMENT && documentProcessingBusy'):]
    require('flowStage = ScanUiStage.PROCESSING' in guarded,
            'queued camera close/back must preserve PROCESSING')

def test_protected_files_unchanged():
    expected = {
        ROOT / 'app/src/main/java/com/abfilepro/app/MainActivity.kt':
            '0cd0d76e4de33fb3ea18542e70cbc28247375a1baf40afa8bd3656dbf5f69517',
        ROOT / 'app/src/main/java/com/abfilepro/app/core/FileUtils.kt':
            '7996c31f5228a63396056deeaa78ba53a65cfba469c3ad42ea2c4220c20e5add',
        ROOT / 'app/src/main/java/com/abfilepro/app/ui/screens/FilesScreen.kt':
            'ea97c1c957a21201328668a6342fc1950a9121470d363408bb925e2a0424b6d4',
    }
    for path, digest in expected.items():
        require(sha(path) == digest, f'protected file changed: {path.name}')

if __name__ == '__main__':
    tests = [v for k, v in sorted(globals().items()) if k.startswith('test_') and callable(v)]
    for test in tests:
        test()
        print('PASS', test.__name__)
    print(f'ALPHA23_CAPTURE_HANDOFF_CONTRACT: {len(tests)}/{len(tests)} PASS')
