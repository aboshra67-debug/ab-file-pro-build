from pathlib import Path
r=Path(__file__).resolve().parents[1]
scan=(r/'app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt').read_text()
main=(r/'app/src/main/java/com/abfilepro/app/MainActivity.kt').read_text()
direct=(r/'app/src/main/java/com/abfilepro/app/ui/screens/DirectFileExplorerPane.kt').read_text()
assert 'onOpenSavedLocation(verifiedSaved)' in scan
assert 'files?dir=${Uri.encode(file.parentFile?.absolutePath.orEmpty())}' in main
assert 'initialDirectoryPath' in direct and 'requested ?: root' in direct
print('ALPHA44_OPEN_SAVED_LOCATION: PASS')
