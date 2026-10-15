from pathlib import Path
import hashlib
import hashlib
ROOT=Path(__file__).resolve().parents[1]
read=lambda p:(ROOT/p).read_text(encoding="utf-8")
gradle=read("app/build.gradle.kts")
scan=read("app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt")
fileutils=read("app/src/main/java/com/abfilepro/app/core/FileUtils.kt")
workspace=read("app/src/main/java/com/abfilepro/app/core/WorkspaceStorageManager.kt")
shortcut=read("app/src/main/java/com/abfilepro/app/core/WorkspaceShortcutManager.kt")
rules=read("app/src/main/java/com/abfilepro/app/core/WorkspaceShortcutRules.kt")
home=read("app/src/main/java/com/abfilepro/app/ui/screens/HomeScreen.kt")

def req(c,m):
    if not c: raise AssertionError(m)
req('versionCode = 103' in gradle, 'versionCode 98 required')
req('2.0.0-alpha42-format-save' in gradle and read('VERSION').strip()=='2.0.0-alpha42-format-save', 'Alpha37 identity mismatch')
req('WorkspaceStorageManager.ensureDefaultWorkspace(context)' in fileutils, 'FileUtils must prefer persistent workspace')
req('.abfilepro_profile.properties' in workspace and 'discoverWorkspace()' in workspace, 'workspace reinstall recovery missing')
req('WorkspaceFolderPickerPane(' in scan and 'alpha37_change_save_location' in scan, 'AB folder picker missing from save flow')
req('destinationDir = targetDirectory' in scan, 'chosen destination not wired to save')
req('FileUtils.confirmSavedFile(context, saved)' in scan, 'verified save confirmation missing')
req('MediaScannerConnection.scanFile' in fileutils, 'device media scan missing')
req('R.drawable.ab_file_manager_icon_color' in shortcut, 'approved file manager icon missing')
req('إدارة ملفات AB برو' in rules, 'exact external manager label missing')
req('FLAG_MATCH_PINNED' in shortcut and 'updateShortcuts' in shortcut, 'existing pinned shortcut must update without duplicate')
req('WorkspaceShortcutManager.requestPinOnce' in home, 'first-run shortcut sync missing')
req((ROOT/'app/src/main/res/drawable-nodpi/ab_file_manager_icon_color.png').is_file(), 'approved icon asset missing')
# protected files specifically left untouched in Alpha37
expected={
    'app/src/main/java/com/abfilepro/app/MainActivity.kt':'0cd0d76e4de33fb3ea18542e70cbc28247375a1baf40afa8bd3656dbf5f69517',
    'app/src/main/java/com/abfilepro/app/ui/screens/FilesScreen.kt':'ea97c1c957a21201328668a6342fc1950a9121470d363408bb925e2a0424b6d4',
}
for rel,digest in expected.items():
    req(hashlib.sha256((ROOT/rel).read_bytes()).hexdigest()==digest, f'protected file changed: {rel}')
print('ALPHA37_PERSISTENT_FILES_CONTRACT: 15/15 PASS')
