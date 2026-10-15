from pathlib import Path
root = Path(__file__).resolve().parents[1]
scan = (root / "app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt").read_text(encoding="utf-8")
gradle = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
version = (root / "VERSION").read_text(encoding="utf-8").strip()
checks = {
    "batch_does_not_auto_open_saved_location": "if (requestedMode != ScanCaptureMode.BATCH)" in scan and "onOpenSavedLocation(verifiedSaved)" in scan,
    "batch_new_scan_preserved": "val restartBatch = selectedMode == ScanCaptureMode.BATCH" in scan and "if (!restartBatch)" in scan,
    "batch_state_reset": "multiPageCapturedPages = emptyList()" in scan and "multiPageReviewIndex = 0" in scan,
    "version_code_162": "versionCode = 162" in gradle,
    "p10_version": version == "2.0.0-alpha98-p10-batch-save-flow",
    "independent_test_package": 'applicationIdSuffix = ".p10batchtrial"' in gradle,
}
failed=[k for k,v in checks.items() if not v]
for k,v in checks.items(): print(f"{'PASS' if v else 'FAIL'} {k}")
if failed: raise SystemExit("failed: "+", ".join(failed))
