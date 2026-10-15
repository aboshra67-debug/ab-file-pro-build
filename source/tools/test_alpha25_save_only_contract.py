from pathlib import Path
import hashlib

ROOT = Path(__file__).resolve().parents[1]
CAM = ROOT / 'app/src/main/java/com/abfilepro/app/ui/screens/ScannerProCamera.kt'
GRADLE = ROOT / 'app/build.gradle.kts'
VERSION = ROOT / 'VERSION'
STRINGS = ROOT / 'app/src/main/res/values/strings_alpha25.xml'

cam = CAM.read_text(encoding='utf-8')
gradle = GRADLE.read_text(encoding='utf-8')
version = VERSION.read_text(encoding='utf-8').strip()
strings = STRINGS.read_text(encoding='utf-8') if STRINGS.exists() else ''


def require(cond, msg):
    if not cond:
        raise AssertionError(msg)


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def test_identity():
    require('versionCode = 75' in gradle, 'versionCode must be 75')
    require('versionName = "2.0.0-alpha25"' in gradle, 'versionName must be alpha25')
    require(version == '2.0.0-alpha25', 'VERSION mismatch')


def test_save_uses_existing_ab_file_workspace():
    require('import com.abfilepro.app.core.FileUtils' in cam, 'FileUtils import missing')
    require('FileUtils.outputDir(context, "scans")' in cam, 'save must target AB File Pro scans folder')
    require('fun saveCapturedDocument()' in cam, 'save helper missing')
    helper = cam[cam.index('fun saveCapturedDocument()'):cam.index('fun loadCapturedDocumentPreview')]
    require('capturedDocumentFile' in helper, 'save helper must use captured CameraX file')
    require('.part' in helper, 'safe temporary output missing')
    require('renameTo' in helper, 'atomic finalization missing')
    require('savedDocumentFile =' in helper, 'saved file state missing')


def test_save_ui_is_local_and_does_not_enter_review_pipeline():
    require('alpha25_save_image' in cam, 'save button text missing')
    require('alpha25_save_success' in cam, 'save success text missing')
    require('onClick = { saveCapturedDocument() }' in cam, 'save button action missing')
    preview_block = cam[cam.index('if (simpleDocumentCapture && capturedDocumentPreview != null)'):cam.index('} else {', cam.index('if (simpleDocumentCapture && capturedDocumentPreview != null)'))]
    require('onPagesCaptured' not in preview_block, 'local save stage must not enter old review pipeline')


def test_save_strings_exist():
    for key in ['alpha25_save_image_ar', 'alpha25_save_success_ar', 'alpha25_save_failed_ar', 'alpha25_saved_location_ar']:
        require(key in strings, f'missing string {key}')


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
    print(f'ALPHA25_SAVE_ONLY_CONTRACT: {len(tests)}/{len(tests)} PASS')
