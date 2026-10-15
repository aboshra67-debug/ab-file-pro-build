from pathlib import Path
p=Path('app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt')
s=p.read_text()
checks={
 'explicit SAVE_OPTIONS stage':'EDITOR, SAVE_OPTIONS, REVIEW' in s,
 'editor transitions to SAVE_OPTIONS':'flowStage = ScanUiStage.SAVE_OPTIONS' in s,
 'save options branch':'ScanUiStage.SAVE_OPTIONS -> {' in s,
 'save goes through saveReviewedScan':'saveReviewedScan(' in s and 'destinationDir = targetDirectory' in s,
 'folder picker returns to SAVE_OPTIONS':'flowStage = ScanUiStage.SAVE_OPTIONS' in s and 'showSaveFolderPicker = false' in s,
}
for k,v in checks.items(): print(('PASS' if v else 'FAIL'),k)
assert all(checks.values())
print(f'ALPHA39_SAVE_TRANSITION_CONTRACT: {sum(checks.values())}/{len(checks)} PASS')
