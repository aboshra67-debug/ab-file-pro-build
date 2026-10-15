from pathlib import Path
import hashlib

ROOT = Path(__file__).resolve().parents[1]
CAM = ROOT / 'app/src/main/java/com/abfilepro/app/ui/screens/ScannerProCamera.kt'
SCAN = ROOT / 'app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt'
PROCESSOR = ROOT / 'app/src/main/java/com/abfilepro/app/core/ScanProcessor.kt'
GRADLE = ROOT / 'app/build.gradle.kts'
VERSION = ROOT / 'VERSION'

cam = CAM.read_text(encoding='utf-8')
scan = SCAN.read_text(encoding='utf-8')
processor = PROCESSOR.read_text(encoding='utf-8')
gradle = GRADLE.read_text(encoding='utf-8')
version = VERSION.read_text(encoding='utf-8').strip()

def require(cond, msg):
    if not cond:
        raise AssertionError(msg)

def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

def test_identity():
    require('versionCode = 103' in gradle, 'versionCode must be 98 for Alpha37 persistent files')
    require('versionName = "2.0.0-alpha42-format-save"' in gradle, 'versionName must be Alpha37 persistent files')
    require('abiFilters += setOf("arm64-v8a")' in gradle, 'ARM64-only native ABI filter must be enabled for size build')
    require(version == '2.0.0-alpha42-format-save', 'VERSION mismatch')

def test_camera_handoff_after_preview():
    require('fun continueCapturedDocument()' in cam, 'handoff helper missing')
    helper = cam[cam.index('fun continueCapturedDocument()'):cam.index('BackHandler {')]
    require('capturedDocumentFile = null' in helper, 'camera must release local ownership')
    require('onPagesCaptured(listOf(page))' in helper, 'captured page is not handed to ScanScreen')
    require('file = source' in helper, 'handoff must preserve original capture file')
    preview = cam[cam.index('if (simpleDocumentCapture && capturedDocumentPreview != null)', cam.index('Surface(tonalElevation = 3.dp)')):]
    require('onClick = { continueCapturedDocument() }' in preview, 'preview Continue action missing')
    require('onClick = { saveCapturedDocument() }' not in preview, 'capture preview must not save before edit flow')

def test_document_routes_to_auto_processing_first():
    camera = scan[scan.index('ScanUiStage.CAMERA -> {'):scan.index('ScanUiStage.PROCESSING -> {')]
    require('openProcessedSingleDocumentReview(capturedPage)' in camera,
            'CameraX single document must enter automatic post-capture processing')
    processor_flow = scan[scan.index('fun openProcessedSingleDocumentReview'):scan.index('fun leaveBoundsForCamera')]
    require('StaticDocumentDetector.detect(bitmap)' in processor_flow, 'automatic edge detection missing')
    require('perspective = detectedQuad' in processor_flow, 'detected quad must drive perspective correction')
    require('filter = ScanFilter.DOCUMENT' in processor_flow, 'automatic document enhancement missing')
    require('flowStage = ScanUiStage.REVIEW' in processor_flow, 'successful auto detection must open corrected review')
    require('flowStage = ScanUiStage.BOUNDS' in processor_flow, 'failed auto detection must fall back to manual bounds')
    require('SingleDocumentBoundsContent(' in scan, 'manual bounds fallback UI missing')
    require('PerspectiveQuadEditor(' in scan, 'four-corner editor missing')

def test_bounds_to_review_applies_document_defaults():
    opener = scan[scan.index('fun openSingleDocumentBounds'):scan.index('fun openProcessedSingleDocumentReview')]
    require('filter = ScanFilter.DOCUMENT' in opener, 'document enhancement default missing')
    require('brightness = 4' in opener, 'brightness default missing')
    require('contrast = 1.08f' in opener, 'contrast default missing')
    require('sharpness = .16f' in opener, 'sharpness default missing')
    bounds = scan[scan.index('ScanUiStage.BOUNDS -> {'):scan.index('ScanUiStage.REVIEW, ScanUiStage.SAVING -> {')]
    helper = scan[scan.index('fun commitSingleDocumentBounds'):scan.index('fun leaveBoundsForCamera')]
    require('flowStage = ScanUiStage.EDITOR' in helper, 'bounds Continue must enter explicit editor stage after commit')
    require('brightness = 4' not in bounds, 'Back to bounds must preserve later user edits instead of resetting them')

def test_bounds_transition_is_single_decode_and_crash_guarded():
    opener = scan[scan.index('fun openSingleDocumentBounds'):scan.index('fun openProcessedSingleDocumentReview')]
    require('renderSourcePreview' not in opener, 'bounds opener must not decode a second preview concurrently')
    content = scan[scan.index('private fun SingleDocumentBoundsContent'):scan.index('private fun ScanReviewContent')]
    require('runCatching {' in content, 'bounds preview load must be crash guarded')
    require('renderSourcePreview(page, 1280)' in content, 'bounds must use one bounded preview decode')
    require('StaticDocumentDetector.detect(loaded).quad' in content, 'document detection must reuse the loaded preview')
    require('onDetectionFinished(null)' in content, 'detection/load failure must finish safely')

def test_stepwise_back_navigation():
    review = scan[scan.index('ScanUiStage.REVIEW, ScanUiStage.SAVING -> {'):scan.index('ScanUiStage.SUCCESS -> {')]
    on_back = review[review.index('onBack = {'): ]
    doc = on_back[on_back.index('if (selectedMode == ScanCaptureMode.DOCUMENT)'):on_back.index('} else if (selectedMode == ScanCaptureMode.TRANSLATION)')]
    require('flowStage = ScanUiStage.BOUNDS' in doc, 'document review Back must return to bounds')
    require('ScanProcessor.cleanup(reviewPages)' not in doc, 'review Back must not destroy current capture')

def test_jpg_pdf_save_and_share_pipeline():
    require('requestedOutputFormat == ScanOutputFormat.JPG' in scan, 'JPG selection missing')
    require('ScanProcessor.saveSinglePageJpeg(' in scan, 'JPG save path missing')
    require('ScanProcessor.createPdf(' in scan, 'PDF save path missing')
    require('saveSuccessFile = verifiedSaved' in scan, 'verified save success state missing')
    require('FileUtils.shareFile(context, file)' in scan, 'generic share missing')
    require('FileUtils.shareFileToWhatsApp(context, file)' in scan, 'WhatsApp share missing')
    require('FileUtils.outputDir(context, "scans")' in processor, 'final outputs must be stored in AB File Pro scans folder')

def test_review_has_manual_bounds_override():
    review_call = scan[scan.index('ScanReviewContent('):scan.index('ScanUiStage.SUCCESS -> {')]
    require('onEditBounds = { index ->' in review_call, 'manual bounds override callback missing')
    require('singleDocumentBoundsPage = page.copy(' in review_call, 'manual bounds must reuse current page')
    card = scan[scan.index('private fun ScanPageReviewCard'):scan.index('@Composable\nprivate fun ScanPageAdjustDialog')]
    require('onEditBounds: () -> Unit' in card, 'review card bounds callback missing')
    require('Text(context.uiText(language, "alpha20_bounds_title"))' in card,
            'review must expose a direct Adjust document edges action')


def test_protected_files_unchanged():
    expected = {
        ROOT / 'app/src/main/java/com/abfilepro/app/MainActivity.kt':
            '0cd0d76e4de33fb3ea18542e70cbc28247375a1baf40afa8bd3656dbf5f69517',
        ROOT / 'app/src/main/java/com/abfilepro/app/ui/screens/FilesScreen.kt':
            'ea97c1c957a21201328668a6342fc1950a9121470d363408bb925e2a0424b6d4',
    }
    for path, digest in expected.items():
        require(sha(path) == digest, f'protected file changed: {path.name}')


def test_document_bounds_has_dedicated_owner_and_no_auto_camera_bounce():
    require('var singleDocumentBoundsPage by remember { mutableStateOf<ScanDraftPage?>(null) }' in scan,
            'single document bounds must own captured page explicitly')
    require('singleDocumentBoundsPage = seeded' in scan,
            'captured document must be transferred into dedicated bounds state')
    bounds = scan[scan.index('ScanUiStage.BOUNDS -> {'):scan.index('ScanUiStage.REVIEW, ScanUiStage.SAVING -> {')]
    require('singleDocumentBoundsPage ?: reviewPages.firstOrNull()' in bounds,
            'document bounds must prefer dedicated captured page')
    require('if (selectedMode != ScanCaptureMode.DOCUMENT)' in bounds,
            'single document must not silently auto-return to Camera when bounds state is transient')
    require('singleDocumentBoundsPage = null' in bounds,
            'bounds ownership must transfer to review on Continue')



def test_fix4_bounds_touch_isolation():
    content = scan[scan.index('private fun SingleDocumentBoundsContent'):scan.index('private fun ScanReviewContent')]
    require('BackHandler(enabled = true) { }' in content,
            'bounds editor must consume implicit Back while adjusting corners')
    require('ScreenTopBar(context.uiText(language, "alpha20_bounds_title"), onBack = null)' in content,
            'bounds top bar must not expose an accidental back target during adjustment')
    bounds = scan[scan.index('ScanUiStage.BOUNDS -> {'):scan.index('ScanUiStage.REVIEW, ScanUiStage.SAVING -> {')]
    page_change = bounds[bounds.index('onPageChange = { updated ->'):bounds.index('onDetectionFinished = { detectedQuad ->')]
    require('singleDocumentBoundsPage = updated' in page_change,
            'manual corner drag must update dedicated bounds state')
    require('reviewPages = listOf(updated)' not in page_change.split('else')[0],
            'manual document corner drag must not mutate generic review navigation state')

def test_fix3_bounds_priority_and_entry_guard():
    require('singleDocumentBoundsEnteredAtMs' in scan, 'bounds entry timestamp guard missing')
    require('elapsed in 0L..699L' in scan, 'immediate bounds navigation guard missing')
    camera = scan[scan.index('ScanUiStage.CAMERA -> {'):scan.index('ScanUiStage.PROCESSING -> {')]
    require('pendingSingleBounds' in camera, 'camera stage must honor pending bounds ownership first')
    require('SingleDocumentBoundsContent(' in camera, 'pending bounds page must render instead of reopening camera')




def test_alpha30_continue_commits_perspective_before_review():
    require('suspend fun commitPerspectiveCrop(context: Context, page: ScanDraftPage): ScanDraftPage' in processor,
            'processor must expose a real perspective-commit stage')
    commit = processor[processor.index('suspend fun commitPerspectiveCrop'):processor.index('suspend fun createPdf')]
    require('perspectiveCorrect(source, quad)' in commit, 'approved four corners must drive perspective correction')
    require('Bitmap.CompressFormat.JPEG, 96' in commit, 'flattened page must be materialized as a JPEG working file')
    require('inJustDecodeBounds = true' in commit and 'bounds.outWidth >= 2' in commit,
            'materialized JPEG must be validated before replacing the capture')
    require('sourceFile.delete()' in commit, 'old temporary capture should be removed only after replacement is valid')
    require('perspective = null' in commit, 'review must receive an already-flattened page')

    helper = scan[scan.index('fun commitSingleDocumentBounds'):scan.index('fun leaveBoundsForCamera')]
    require('flowStage = ScanUiStage.PROCESSING' in helper, 'Continue must show processing while the real crop is committed')
    require('ScanProcessor.commitPerspectiveCrop(context, adjusted)' in helper, 'Continue must call the real perspective commit')
    require('flowStage = ScanUiStage.EDITOR' in helper, 'successful commit must open Editor directly')
    require('flowStage = ScanUiStage.BOUNDS' in helper, 'failed commit must safely return to Bounds')

def test_alpha28_opencv_detector_is_primary_with_safe_fallback():
    detector = (ROOT / 'app/src/main/java/com/abfilepro/app/core/StaticDocumentDetector.kt').read_text(encoding='utf-8')
    gradle = (ROOT / 'app/build.gradle.kts').read_text(encoding='utf-8')
    require('implementation("org.opencv:opencv:4.14.0")' in gradle, 'official OpenCV Android dependency missing')
    require('OpenCVLoader.initLocal()' in detector, 'OpenCV must initialize before contour detection')
    require('Imgproc.findContours' in detector, 'contour-based document detection missing')
    require('Imgproc.approxPolyDP' in detector, 'four-corner polygon approximation missing')
    require('approx.total() == 4L' in detector, 'detector must require a 4-corner contour')
    require('Imgproc.isContourConvex' in detector, 'detected document contour must be convex')
    require('legacy.copy(' in detector and 'source = "legacy-fallback"' in detector, 'legacy safe fallback missing')
    require('flowStage = ScanUiStage.REVIEW' in scan, 'accepted auto-detection must still go directly to corrected review')

if __name__ == '__main__':
    tests = [v for k, v in sorted(globals().items()) if k.startswith('test_') and callable(v)]
    for test in tests:
        test()
        print('PASS', test.__name__)
print(f'ALPHA32_DIRECT_EDITOR_BASE_CONTRACT: {len(tests)}/{len(tests)} PASS')
