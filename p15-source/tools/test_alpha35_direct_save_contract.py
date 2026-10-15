from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
s=(ROOT/'app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt').read_text()
assert 'onSaveAndContinue: ((ScanDraftPage) -> Unit)? = null' in s
assert 'saveReviewedScan(' in s
assert 'if (busy || activeSaveJob?.isActive == true) return' in s
assert 'enabled = !busy' in s
assert 'pendingSingleDocumentSavePage = updated' in s
assert ('showSingleDocumentSaveOptions = true' in s) or ('flowStage = ScanUiStage.SAVE_OPTIONS' in s)
assert 'returnToEditorOnFailure = true' in s
assert 'versionCode = 103' in (ROOT/'app/build.gradle.kts').read_text()
assert '2.0.0-alpha42-format-save' in (ROOT/'VERSION').read_text()
print('ALPHA35_DIRECT_SAVE_BASE_PRESERVED_UNDER_ALPHA37: PASS')
