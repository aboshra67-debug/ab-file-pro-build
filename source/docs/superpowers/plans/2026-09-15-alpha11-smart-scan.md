# Alpha11 Smart Scan Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the approved Alpha11 task-driven scanner hub with seven direct actions, 10-page capture limits, file-manager storage, post-capture editing, live barcode reading, and translation.

**Architecture:** Keep `ScanScreen` as the single route entry so `MainActivity` remains untouched. Reuse `ScannerProCamera`, `ScanReviewContent`, `ScanProcessor`, existing `BarcodeScreen`, and existing FileUtils workspace behavior. Add small focused components for hub UI, translated-text processing/result UI, and special save behavior for ID card.

**Tech Stack:** Android/Kotlin, Jetpack Compose, CameraX, existing scan processor, Tesseract OCR, ML Kit language-id + translation, existing FileUtils workspace.

**Spec:** `docs/superpowers/specs/2026-09-15-alpha11-smart-scan-design.md`

## Global Constraints
- Do not modify `MainActivity.kt`, `FileUtils.kt`, or `FilesScreen.kt`.
- Package remains `com.abfilepro.app`.
- Version becomes `2.0.0-alpha11`, versionCode `61`.
- Batch and images-to-PDF maximum is exactly 10 pages/images.
- All saved outputs use existing AB File Pro workspace output helpers.
- Patch existing working behavior rather than rewrite it.

---

### Task 1: Regression contracts and mode limits
**Files:**
- Create: `tools/tests/test_alpha11_smart_scan_contract.py`
- Modify: `app/src/main/java/com/abfilepro/app/core/ScanProcessor.kt`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Produces exact mode limits and Alpha11 version contract for later tasks.

- [ ] Write a failing contract that asserts version 61/alpha11, BATCH=10, PHOTO=10, and protected files unchanged via recorded hashes.
- [ ] Run the contract and verify it fails on Alpha10 values.
- [ ] Change only version fields and the two page limits.
- [ ] Run the contract and verify those assertions pass.

### Task 2: Image-backed direct-action hub
**Files:**
- Create: `app/src/main/java/com/abfilepro/app/ui/screens/SmartScanHub.kt`
- Create: `app/src/main/res/drawable-nodpi/scan_mode_*.webp` (7 images)
- Modify: `app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- `SmartScanHub(onAction: (SmartScanAction) -> Unit, onBack: () -> Unit)`
- `enum class SmartScanAction { DOCUMENT, BATCH, ID_CARD, RECEIPT, BARCODE, TRANSLATION, IMAGES_TO_PDF }`

- [ ] Extend the Alpha11 contract to require seven action labels and image resources.
- [ ] Verify the new assertions fail.
- [ ] Add seven image-backed cards with direct tap actions and no extra start button.
- [ ] Wire `ScanScreen` internal state to launch the correct existing flow without changing MainActivity.
- [ ] Verify the contract passes.

### Task 3: Still-capture flow and images-to-PDF
**Files:**
- Modify: `app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt`
- Modify: `app/src/main/java/com/abfilepro/app/ui/screens/ScannerProCamera.kt`

**Interfaces:**
- Existing `ScannerProCamera` and `ScanReviewContent` are reused.
- `PHOTO` mode represents `صور إلى PDF` and supports camera/gallery with max 10.

- [ ] Add contract assertions for direct mode mapping and no legacy scanner hub controls.
- [ ] Verify failure before implementation.
- [ ] Map document=1, batch=10, ID=2, receipt=1, translation=1, photo=10.
- [ ] Make direct cards start camera immediately; image-to-PDF may also import from gallery.
- [ ] Verify captured pages always reach review/edit before save.

### Task 4: Card front/back save
**Files:**
- Modify: `app/src/main/java/com/abfilepro/app/core/CardScanProcessor.kt`
- Modify: `app/src/main/java/com/abfilepro/app/core/ScanProcessor.kt`
- Modify: `app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt`

**Interfaces:**
- Add `ScanProcessor.exportEditedPageToCache(context, page, quality): File`.
- Reuse `CardScanProcessor.saveCombinedPdf(...)`.

- [ ] Add a contract for the edited-page export and combined-card save branch.
- [ ] Verify failure.
- [ ] Export the two reviewed/edited sides to temporary images and build one combined PDF page.
- [ ] Cleanup temporary files and review drafts safely.
- [ ] Verify contract passes.

### Task 5: Live barcode direct route
**Files:**
- Modify: `app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt`
- Modify: `app/src/main/java/com/abfilepro/app/ui/screens/BarcodeScreen.kt`

**Interfaces:**
- `BarcodeScreen(onBack, initialTab = 0, readerOnly = false)`; smart-scan uses `readerOnly=true`.

- [ ] Add contract requiring direct embedded reader-only barcode launch.
- [ ] Verify failure.
- [ ] Preserve existing full Barcode screen for other app routes while adding a reader-only mode used by ScanScreen.
- [ ] Add URL-open action when the detected value is http/https.
- [ ] Verify contract passes.

### Task 6: Translation after edit
**Files:**
- Create: `app/src/main/java/com/abfilepro/app/core/TranslationProcessor.kt`
- Create: `app/src/main/java/com/abfilepro/app/ui/screens/ScanTranslationResult.kt`
- Modify: `app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- `TranslationProcessor.translateReviewedPage(context, page, target): TranslationResult`
- `TranslationTarget { ARABIC, ENGLISH, FRENCH }`
- Result contains detected source, extracted text, translated text, and a workspace text file.

- [ ] Add contract assertions for ML Kit dependencies and translation processor/result screen.
- [ ] Verify failure.
- [ ] Add bundled language-id and ML Kit translation dependencies.
- [ ] OCR edited image, identify language, download translation model if needed, translate, save UTF-8 text under `translations`.
- [ ] Show result with target selector, save state, copy/share actions.
- [ ] Verify contract passes.

### Task 7: Save/share completion UI and protected-file verification
**Files:**
- Modify: `app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `PATCH_REPORT_V2_ALPHA11_SMART_SCAN.txt`

**Interfaces:**
- `lastSavedFile: File?` exposes open/share after successful save.

- [ ] Add contract assertions that saved PDF flow exposes share and that FileUtils/MainActivity/FilesScreen remain unchanged.
- [ ] Verify failure.
- [ ] Add post-save Open/Share panel without editing protected files.
- [ ] Re-run all Alpha06-Alpha10 scanner/home contracts plus Alpha11.
- [ ] Build release, inspect package/version, sign with original key, verify signature and package.
