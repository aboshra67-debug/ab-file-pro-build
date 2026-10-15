# Single Document Professional Scan Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route only Single Document capture through the already-integrated Google ML Kit Document Scanner while preserving AB File Pro review/save behavior and providing the existing CameraX scanner only as an explicit launch-failure fallback.

**Architecture:** `ScanScreen.kt` owns a dedicated ML Kit client with page limit 1 and JPEG-only output for `ScanCaptureMode.DOCUMENT`. Initial capture and retake use that professional scanner; successful JPEGs are cached directly as `ScanDraftPage` objects and shown in existing review without re-running `DocumentFrameAnalyzer`. If ML Kit cannot start, a clear dialog offers the existing `ScannerProCamera` fallback instead of silently failing.

**Tech Stack:** Kotlin, Jetpack Compose, Activity Result API, Google ML Kit Document Scanner 16.0.0, existing `ScanProcessor` review/save pipeline, Python static contract tests, Gradle Android release build.

**Spec:** `docs/superpowers/specs/2026-09-16-single-document-professional-scan-design.md`

## Global Constraints

- Change `ScanCaptureMode.DOCUMENT` only.
- Do not modify `MainActivity.kt`.
- Do not modify `FileUtils.kt`.
- Do not modify `FilesScreen.kt`.
- Do not modify `DocumentFrameAnalyzer.kt`.
- Do not retune `ScannerProCamera.kt`.
- Do not change package `com.abfilepro.app`.
- Keep version `2.0.0-alpha13`, versionCode `63` for this corrective build.
- Preserve all other scan-mode routes and save/share behavior.
- No second heavy document-detection/crop pass before AB review.

---

### Task 1: Lock Single Document routing with a failing contract

**Files:**
- Create: `tools/test_single_document_professional_scan_contract.py`
- Read: `app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt`

**Interfaces:**
- Consumes: current `SmartScanAction.DOCUMENT` route in `ScanScreen.kt`.
- Produces: executable contract that fails until dedicated professional scanner routing exists.

- [ ] **Step 1: Write the failing static contract**

The test must assert all of the following production markers:

```python
assert '.setPageLimit(1)' in scan
assert 'RESULT_FORMAT_JPEG' in scan
assert 'singleDocumentScanner' in scan
assert 'launchSingleDocumentScanner' in scan
assert 'SmartScanAction.DOCUMENT -> launchSingleDocumentScanner()' in scan
assert 'SmartScanAction.BATCH -> launchMode(ScanCaptureMode.BATCH)' in scan
assert 'SmartScanAction.ID_CARD -> launchMode(ScanCaptureMode.ID_CARD)' in scan
assert 'SmartScanAction.RECEIPT -> launchMode(ScanCaptureMode.RECEIPT)' in scan
assert 'singleDocumentRetakeIndex' in scan
assert 'showSingleDocumentFallback' in scan
```

Also verify protected SHA-256 hashes remain exactly:

```text
MainActivity.kt 0cd0d76e4de33fb3ea18542e70cbc28247375a1baf40afa8bd3656dbf5f69517
FileUtils.kt     7996c31f5228a63396056deeaa78ba53a65cfba469c3ad42ea2c4220c20e5add
FilesScreen.kt   ea97c1c957a21201328668a6342fc1950a9121470d363408bb925e2a0424b6d4
```

- [ ] **Step 2: Run the new contract and verify RED**

Run:

```bash
python3 tools/test_single_document_professional_scan_contract.py
```

Expected: FAIL because the dedicated single-document client/function/route do not yet exist.

---

### Task 2: Implement dedicated professional Single Document capture

**Files:**
- Modify: `app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt`
- Modify: `app/src/main/res/values/strings_single_document_professional.xml`

**Interfaces:**
- Consumes: `GmsDocumentScanning`, `GmsDocumentScannerOptions`, `ScanProcessor.cacheScannerPages`, `ScanReviewContent`, `ScannerProCamera`.
- Produces: `launchSingleDocumentScanner(retakeIndex: Int? = null)` with page-limit 1 and JPEG-only result.

- [ ] **Step 1: Add dedicated state and options**

Add state in `ScanScreen`:

```kotlin
var singleDocumentRetakeIndex by remember { mutableStateOf<Int?>(null) }
var showSingleDocumentFallback by remember { mutableStateOf(false) }
```

Add dedicated options/client:

```kotlin
val singleDocumentOptions = remember {
    GmsDocumentScannerOptions.Builder()
        .setGalleryImportAllowed(true)
        .setPageLimit(1)
        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
        .build()
}
val singleDocumentScanner = remember { GmsDocumentScanning.getClient(singleDocumentOptions) }
```

Do not modify the existing 50-page scanner used by legacy/fallback flows.

- [ ] **Step 2: Add the dedicated activity-result launcher**

On `RESULT_OK`, read the first returned page only, cache it through `ScanProcessor.cacheScannerPages(context, listOf(uri))`, apply `ScanCaptureMode.DOCUMENT.defaultFilter`, and:

```kotlin
if (singleDocumentRetakeIndex != null) {
    // Replace the requested review page only after the new cached page exists.
} else {
    // Cleanup old review pages and set the single returned page.
}
```

On cancellation, keep the old review page during a retake and otherwise remain on the Smart Scan hub. Always clear `busy` and `singleDocumentRetakeIndex` safely.

- [ ] **Step 3: Add launch function with explicit failure fallback**

Implement:

```kotlin
fun launchSingleDocumentScanner(retakeIndex: Int? = null) {
    selectedMode = ScanCaptureMode.DOCUMENT
    cameraPages = emptyList()
    cameraOpen = false
    if (activity == null) {
        showSingleDocumentFallback = true
        return
    }
    singleDocumentRetakeIndex = retakeIndex
    busy = true
    singleDocumentScanner.getStartScanIntent(activity)
        .addOnSuccessListener { sender ->
            singleDocumentLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
        .addOnFailureListener {
            busy = false
            singleDocumentRetakeIndex = null
            showSingleDocumentFallback = true
        }
}
```

The fallback dialog must have two explicit choices: use the existing camera or cancel. Choosing existing camera sets `selectedMode = DOCUMENT`, clears temporary camera pages, and sets `cameraOpen = true`. Cancel leaves the user in the hub or existing review without deleting data.

- [ ] **Step 4: Route only DOCUMENT to the dedicated scanner**

Change only this Smart Scan action:

```kotlin
SmartScanAction.DOCUMENT -> launchSingleDocumentScanner()
```

Leave BATCH, ID_CARD, RECEIPT, TRANSLATION, IMAGES_TO_PDF unchanged.

- [ ] **Step 5: Make DOCUMENT review-back and retake professional**

For `selectedMode == DOCUMENT`:
- Review Back: cleanup the one review page and return to hub.
- Retake: keep current page until professional scanner returns successfully; call `launchSingleDocumentScanner(index)`.
- Add-pages remains unavailable because `maxPages == 1`.

For all other modes, retain existing back/retake behavior unchanged.

- [ ] **Step 6: Add localized fallback strings**

Create Arabic/English/French strings for:
- scanner unavailable title
- professional scanner could not start message
- use existing camera
- cancel

Use `context.uiText(language, key)`; no hardcoded UI language strings in `ScanScreen.kt`.

- [ ] **Step 7: Run new contract and verify GREEN**

```bash
python3 tools/test_single_document_professional_scan_contract.py
```

Expected: PASS.

---

### Task 3: Regression verification and release build

**Files:**
- Verify only; no unrelated production edits.

**Interfaces:**
- Consumes: completed source from Tasks 1–2.
- Produces: verified safe source archive and signed release APK.

- [ ] **Step 1: Run all local static contracts**

```bash
python3 tools/test_single_document_professional_scan_contract.py
python3 tools/test_camera_hotfix2_contract.py
python3 tools/test_camera_detection_hotfix_contract.py
python3 tools/test_alpha13_back_fix_contract.py
python3 tools/test_alpha13_scan_patch_contract.py
python3 tools/tests/test_alpha11_compile_regression_contract.py
python3 tools/tests/test_home_scanner_entry_contract.py
python3 tools/tests/test_scanner_stage1_contract.py
python3 tools/tests/test_visual_button_icons_contract.py
```

Expected: all PASS.

- [ ] **Step 2: Recheck protected-file hashes and version identity**

Expected hashes are the three values listed in Task 1. Also verify:

```text
applicationId = com.abfilepro.app
versionCode = 63
versionName = 2.0.0-alpha13
```

- [ ] **Step 3: Build in CI with original signer**

Run Gradle release unit tests and assemble:

```bash
gradle --no-daemon --stacktrace clean :app:testReleaseUnitTest :app:assembleRelease
```

Then verify with Android build tools:

```bash
zipalign -c -v 4 app-release.apk
aapt dump badging app-release.apk
apksigner verify --verbose --print-certs app-release.apk
```

Require package/version above and signer SHA-256:

```text
DD:3E:88:21:F2:4C:D3:7C:96:10:21:B0:C2:7C:E7:82:6B:9A:92:A7:D3:B0:95:8B:D0:09:6A:E3:3B:93:4D:5B
```

- [ ] **Step 4: Package safe source**

Exclude build output, `.gradle`, local.properties, keystores/signing secrets, and caches. Deliver source ZIP + signed APK + patch report + SHA256 manifest.
