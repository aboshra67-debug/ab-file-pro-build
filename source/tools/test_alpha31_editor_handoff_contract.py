from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
scan = (ROOT / 'app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt').read_text()
gradle = (ROOT / 'app/build.gradle.kts').read_text()
version = (ROOT / 'VERSION').read_text().strip()

def require(cond, msg):
    if not cond:
        raise AssertionError(msg)

require('versionCode = 92' in gradle, 'Alpha31 versionCode must be 92')
require('versionName = "2.0.0-alpha31-editor-handoff"' in gradle, 'Alpha31 versionName mismatch')
require(version == '2.0.0-alpha31-editor-handoff', 'VERSION mismatch')
require('var autoOpenEditorPageId by remember { mutableStateOf<Long?>(null) }' in scan, 'missing one-shot editor handoff state')
require('autoOpenEditorPageId = flattened.id' in scan, 'perspective success must request editor handoff')
require('autoOpenEditor = autoOpenEditorPageId == page.id' in scan, 'review page must route handoff to matching page')
require('LaunchedEffect(autoOpenEditor, page.id)' in scan and 'showEditor = true' in scan, 'review card must auto-open existing editor')
require('ScanPageAdjustDialog(' in scan, 'existing full editor must remain in use')
require('ScanProcessor.commitPerspectiveCrop(context, adjusted)' in scan, 'perspective commit must remain before editor handoff')
print('ALPHA31_EDITOR_HANDOFF_CONTRACT: PASS')
