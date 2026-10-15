"""Guard Arabic OCR patch without requiring Android in local runtime."""
from pathlib import Path
r=Path(__file__).resolve().parents[1]
ocr=(r/'app/src/main/java/com/abfilepro/app/core/OcrProcessor.kt').read_text()
office=(r/'app/src/main/java/com/abfilepro/app/core/OfficeTools.kt').read_text()
screen=(r/'app/src/main/java/com/abfilepro/app/ui/screens/ConvertScreen.kt').read_text()
review=(r/'app/src/main/java/com/abfilepro/app/ui/screens/ImageWordReviewDialog.kt').read_text()
checks={
 'original_recognition_kept':'suspend fun recognize(' in ocr,
 'original_office_ocr_kept':'suspend fun imageToDocx(' in office,
 'word_special_recognition':'suspend fun recognizeForWord(' in ocr,
 'actual_image_region_cropped':'loadBitmap(context, uri, maxDimension = 3200, crop = crop)' in ocr,
 'explicit_cropped_region_block_psm':'if (!crop.isEmpty()) api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)' in ocr,
 'bounded_upscale':'coerceIn(1f, 2f)' in ocr,
 'word_does_not_export_automatically':'OfficeTools.reviewedTextToDocx(context, editedText)' in screen,
 'review_is_editable':'onValueChange = { reviewed = it }' in review,
 'manual_cropping':all(s in review for s in ('cropLeft','cropRight','cropTop','cropBottom')),
 'no_false_success_for_empty_text':'if (result.isBlank())' in review,
 'cancelable_coroutine':'catch (_: CancellationException)' in review,
 'mixed_arabic_english_explicit':'OcrProcessor.Language.ARABIC_ENGLISH' in screen,
 'save_is_still_confirmed':'ConversionDraftManager.stage(context, generated)' in screen and 'FileUtils.confirmSavedFile(context, it)' in screen,
}
for name,ok in checks.items(): print(f"[{'PASS' if ok else 'FAIL'}] {name}")
assert all(checks.values())
print(f"Arabic OCR review contract: {len(checks)} PASS")
