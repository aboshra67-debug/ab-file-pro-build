from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
scan=(ROOT/'app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt').read_text()
gradle=(ROOT/'app/build.gradle.kts').read_text()
version=(ROOT/'VERSION').read_text().strip()

def req(cond,msg):
    if not cond: raise AssertionError(msg)

req('versionCode = 103' in gradle, 'versionCode 98 required')
req('versionName = "2.0.0-alpha42-format-save"' in gradle, 'Alpha37 versionName required')
req(version == '2.0.0-alpha42-format-save', 'VERSION mismatch')
req(('showSingleDocumentSaveOptions = true' in scan) or ('flowStage = ScanUiStage.SAVE_OPTIONS' in scan), 'editor must open save options')
req('ScanSaveOptionsDialog(' in scan, 'save options dialog required')
req('initialFormat = singleDocumentOutputFormat' in scan, 'format must use current selection')
req('saveDirectory = currentSaveDirectory' in scan and 'WorkspaceFolderPickerPane(' in scan, 'save location picker must reflect actual persistent folder')
req('returnToEditorOnFailure = true' in scan, 'failed direct save must return to editor')
req('if (busy || activeSaveJob?.isActive == true) return' in scan, 'duplicate save guard required')
req('BackHandler(enabled = true) { }' in scan, 'success screen must consume accidental system back')
req('alpha36_back_to_scan_home' in scan, 'explicit home action required')
req('saveSuccessFile = verifiedSaved' in scan and 'flowStage = ScanUiStage.SUCCESS' in scan, 'verified success state required')
req('pendingSingleDocumentSavePage = null' in scan, 'pending page must clear after save')
print('ALPHA36_SAVE_STABILITY_CONTRACT: 12/12 PASS')
