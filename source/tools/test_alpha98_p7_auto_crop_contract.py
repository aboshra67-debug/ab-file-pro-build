"""Source-level safe patch checks; device OCR quality is tested separately."""
from pathlib import Path
r=Path(__file__).resolve().parents[1]
ocr=(r/'app/src/main/java/com/abfilepro/app/core/OcrProcessor.kt').read_text(encoding='utf-8')
ui=(r/'app/src/main/java/com/abfilepro/app/ui/screens/ImageWordReviewDialog.kt').read_text(encoding='utf-8')
screen=(r/'app/src/main/java/com/abfilepro/app/ui/screens/ConvertScreen.kt').read_text(encoding='utf-8')
gradle=(r/'app/build.gradle.kts').read_text(encoding='utf-8')
checks={
  'optional_smart_crop':'suspend fun suggestDialogCrop(' in ocr,
  'off_ui_thread':'suggestDialogCrop(context: Context, uri: Uri): CropSpec? = withContext(Dispatchers.IO)' in ocr,
  'guard_screenshots':'center - outside < 55' in ocr and 'if (center < 175' in ocr,
  'guard_normal_images':'return null' in ocr and 'right - left <' in ocr and 'bottom - top <' in ocr,
  'no_auto_crop_without_consent':'OcrProcessor.suggestDialogCrop(context, uri)' in ui and 'تحديد النافذة تلقائيًا' in ui,
  'manual_crop_preserved':'CropSlider("قص من الأعلى"' in ui and 'CropSlider("قص من اليمين"' in ui,
  'manual_reset':'Text("إلغاء القص")' in ui,
  'text_review_preserved':'onValueChange = { reviewed = it }' in ui and 'Text("إنشاء Word")' in ui,
  'word_flow_preserved':'OfficeTools.reviewedTextToDocx(context, editedText)' in screen,
  'no_background_overwrite':'source.recycle()' not in ui,
  'package_isolated':'applicationIdSuffix = ".ocrpreviewp7"' in gradle,
  'production_identity_preserved':'applicationId = "com.abfilepro.app"' in gradle and 'versionCode = 159' in gradle,
}
for k,v in checks.items():print(f"[{'PASS' if v else 'FAIL'}] {k}")
assert all(checks.values())
print(f'P7 OCR auto-dialog safe patch: {len(checks)} PASS')
