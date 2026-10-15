from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]

def read(path): return (ROOT / path).read_text(encoding='utf-8')

def must(text, needle, label):
    assert needle in text, f"missing {label}: {needle}"

camera = read('app/src/main/java/com/abfilepro/app/ui/screens/ScannerProCamera.kt')
scan = read('app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt')
processor = read('app/src/main/java/com/abfilepro/app/core/ScanProcessor.kt')
updater = read('app/src/main/java/com/abfilepro/app/core/AppUpdateManager.kt')
main = read('app/src/main/java/com/abfilepro/app/MainActivity.kt')
gradle = read('app/build.gradle.kts')
manifest = read('app/src/main/AndroidManifest.xml')
server = read('family-sync-server/main.py')

must(camera, 'retireDocumentPreview()', 'batch preview retirement')
must(camera, 'metrics = null', 'batch stale metrics reset')
must(camera, 'ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY', 'scanner quality capture')
must(camera, 'analysisExecutor.execute {', 'off-main scanner processing')
must(camera, 'PreviewView.ScaleType.FIT_CENTER', 'preview fit mode')
must(camera, 'scaledWidth = size.width\n                                scaledHeight = size.width / imageAspect', 'FIT_CENTER wide mapping')
must(camera, 'scaledHeight = size.height\n                                scaledWidth = size.height * imageAspect', 'FIT_CENTER tall mapping')
must(processor, 'if (preserveSourceDetail)', 'document detail flag used')
must(processor, '3200', 'safe detail cap')
must(scan, 'fun cancelDocumentProcessing()', 'processing cancel')
must(scan, 'TextButton(', 'processing cancel button')

must(updater, 'verifySha256', 'update hash verification')
must(updater, 'verifyArchiveIdentity', 'update package/version verification')
must(updater, 'Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES', 'install permission flow')
must(updater, 'FileProvider.getUriForFile', 'safe installer URI')
must(main, 'AppUpdateManager.checkForUpdate', 'startup update check')
must(main, 'AppUpdateManager.resumePendingInstall', 'resume update install')
must(manifest, 'android.permission.REQUEST_INSTALL_PACKAGES', 'install packages permission')
must(server, '@app.get("/app-update")', 'server update manifest endpoint')
must(server, 'AB_FILE_PRO_UPDATE_SHA256', 'server hash configuration')
must(gradle, 'versionCode = 161', 'P9 increasing version code')
must(gradle, 'applicationIdSuffix = ".ocrpreviewp8"', 'P8 preview update-compatible package')

ET.parse(ROOT / 'app/src/main/AndroidManifest.xml')
ET.parse(ROOT / 'app/src/main/res/values/strings_p9_scanner_update.xml')
print('PASS alpha98 P9 scanner + safe updater contract')
