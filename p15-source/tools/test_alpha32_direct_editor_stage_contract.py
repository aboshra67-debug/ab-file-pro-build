from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
scan = (ROOT / "app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt").read_text()
gradle = (ROOT / "app/build.gradle.kts").read_text()
version = (ROOT / "VERSION").read_text().strip()

def require(cond, msg):
    if not cond:
        raise AssertionError(msg)

require("versionCode = 93" in gradle, "Alpha32 versionCode must be 93")
require('versionName = "2.0.0-alpha32-direct-editor-stage"' in gradle, "Alpha32 versionName mismatch")
require(version == "2.0.0-alpha32-direct-editor-stage", "VERSION mismatch")
require("ScanUiStage.EDITOR" in scan, "missing explicit EDITOR stage")
require("flowStage = ScanUiStage.EDITOR" in scan, "perspective success must enter EDITOR directly")
require("ScanProcessor.commitPerspectiveCrop(context, adjusted)" in scan, "perspective commit must remain before editor stage")
editor_block = scan[scan.index("ScanUiStage.EDITOR ->"):scan.index("ScanUiStage.REVIEW, ScanUiStage.SAVING ->")]
require("ScanPageAdjustDialog(" in editor_block, "EDITOR stage must render the existing editor directly")
require("flowStage = ScanUiStage.REVIEW" in editor_block, "editor apply/dismiss must return to review")
require("autoOpenEditorPageId = flattened.id" not in scan, "Alpha31 nested-card handoff must not drive Alpha32")
print("ALPHA32_DIRECT_EDITOR_STAGE_CONTRACT: PASS")
