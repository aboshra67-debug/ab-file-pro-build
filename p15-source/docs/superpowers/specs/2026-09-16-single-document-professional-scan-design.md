# Single Document Professional Scan — Design

## Goal
Make **مستند واحد / Single Document** reliable before touching any other scan mode. The capture button must always allow the user to take one page, while document-edge detection, auto-crop, perspective correction, manual correction fallback, and AB File Pro review remain available.

## Scope
This design changes **ScanCaptureMode.DOCUMENT only**.

Out of scope for this phase:
- Batch / multi-page
- ID card
- Receipt
- Translation
- Photo-to-PDF
- Barcode
- Any redesign of the rest of Smart Scan

## Non-negotiable project constraints
- Do not modify `MainActivity.kt`.
- Do not modify `FileUtils.kt`.
- Do not modify `FilesScreen.kt`.
- Do not delete working scan modes or working save/share behavior.
- Keep package `com.abfilepro.app`.
- Keep PostgreSQL / unrelated modules untouched.
- Priorities: page-opening speed, safety, stability, then new behavior.
- Safe patch only.

## Current-state findings
The project already contains `com.google.android.gms:play-services-mlkit-document-scanner:16.0.0` and already creates a `GmsDocumentScannerOptions` client in `ScanScreen.kt`. The same project also has working ML Kit document-scanner flows in `BusinessCardScreen.kt`, `ReceiptInvoiceScreen.kt`, and `CardScanScreen.kt`.

`ScanScreen.kt` currently routes `DOCUMENT` through `launchMode()`, which sets `cameraOpen = true` and therefore enters `ScannerProCamera`. The manual CameraX path then depends on `DocumentFrameAnalyzer` for its auto-detection overlay and crop metadata. This is the path that has produced unstable detection on the user's real table/document test.

## Selected architecture
For **Single Document only**, use the already-integrated **Google ML Kit Document Scanner** as the primary capture surface. Do not reuse the custom `DocumentFrameAnalyzer` for this mode.

The professional flow will be:

1. User taps **مستند واحد** in `SmartScanHub`.
2. `ScanScreen` starts a dedicated single-document ML Kit scanner configured with:
   - page limit = 1
   - gallery import allowed
   - scanner mode = FULL
   - JPEG result required for AB File Pro review
3. The scanner UI provides manual shutter capture plus automatic edge detection, crop, perspective correction, and manual corner adjustment.
4. On success, the returned JPEG is copied into the existing `ScanDraftPage` cache using `ScanProcessor.cacheScannerPages()`.
5. AB File Pro immediately opens the existing `ScanReviewContent` with that page.
6. Filters, adjustments, reset, save, share, and PDF creation continue through the existing AB File Pro review/save path.

## Manual capture guarantee
The manual shutter must not depend on AB File Pro's custom detection confidence. In the primary single-document flow, the shutter is owned by the ML Kit scanner UI, so capture is not blocked by `DocumentFrameAnalyzer.captureReady`.

If the professional scanner cannot start on the device, AB File Pro must not silently fail. It will show a clear failure message and offer the existing CameraX camera as a fallback manual-capture path. This fallback is exceptional; it is not the primary single-document route.

## Auto detection / auto crop behavior
Automatic detection, crop, and perspective correction for Single Document come from the ML Kit document scanner, not from the current custom contour/threshold logic.

This avoids tuning more thresholds against one sample document and prevents table/grid lines from being treated as the primary production detection strategy for this mode.

## Manual correction fallback
If automatic edges are imperfect, the scanner's manual crop/corner adjustment remains available before returning the JPEG to AB File Pro. After return, AB File Pro's existing review editor remains available for rotate/filter/adjustment work.

## Preview behavior
Do not run a second heavy AB File Pro crop/detection pass before showing the returned page. The returned scanner JPEG is already the processed document image. Cache it and enter `ScanReviewContent` directly.

This keeps the review transition simple and avoids a blank review screen caused by waiting for another detection pipeline.

## Navigation behavior
- Smart Scan hub → Single Document scanner
- Scanner success → AB File Pro review
- Scanner cancel → Smart Scan hub
- Review back → Smart Scan hub for this one-page professional flow
- Review save → existing save flow
- No back action should jump directly to Home unless the user backs out from the Smart Scan hub itself.

## Files intended to change
- `app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt`
  - add a dedicated single-document scanner client/options
  - route only `SmartScanAction.DOCUMENT` into that flow
  - handle success/cancel/failure cleanly
- `app/src/main/java/com/abfilepro/app/core/ScanInteractionRules.kt`
  - only if a small explicit rule is needed for DOCUMENT review-back behavior; otherwise leave unchanged
- `app/src/test/...`
  - add regression coverage for DOCUMENT routing and back behavior
- `tools/...`
  - add a static contract verifying the production routing and protected-file hashes

Files explicitly not to change:
- `MainActivity.kt`
- `FileUtils.kt`
- `FilesScreen.kt`
- `DocumentFrameAnalyzer.kt` in this phase
- `ScannerProCamera.kt` in this phase unless required only for the launch-failure fallback wiring; its capture/detection implementation must not be retuned

## Test requirements
Before production changes, add a failing regression test proving that `DOCUMENT` currently routes to the custom camera rather than the professional scanner.

After implementation, verify:
1. `DOCUMENT` routes to the dedicated professional scanner.
2. Dedicated scanner page limit is exactly 1.
3. JPEG page result enters `reviewPages` and opens AB review.
4. Cancel returns to Smart Scan hub.
5. Scanner-start failure exposes fallback instead of leaving the UI busy/stuck.
6. Other scan actions keep their existing routes.
7. Protected file SHA-256 hashes are unchanged.
8. Existing unit/static contracts still pass.
9. `:app:testReleaseUnitTest` passes.
10. `:app:assembleRelease` succeeds.
11. APK package/version/signature are validated before delivery.

## Acceptance criteria on the phone
Using the same real paper shown in the user's videos:
- tapping **مستند واحد** opens a scanner that has a working shutter button;
- pressing the shutter captures the page even if edge detection is not perfect;
- automatic document crop/perspective correction is available;
- manual corner correction is available;
- completing the scan shows the captured page in AB File Pro review;
- there is no blank review page waiting on a second detection pass;
- Back returns within the scan flow, not directly to the app Home screen.
