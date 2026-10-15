"""Non-Android static regression checks for AB98 P4 -> conversion-center P5 safe patch."""
from pathlib import Path
import re
ROOT = Path(__file__).resolve().parents[1]
read = lambda f: (ROOT / f).read_text(encoding="utf-8")
screen = read('app/src/main/java/com/abfilepro/app/ui/screens/ConvertScreen.kt')
main = read('app/src/main/java/com/abfilepro/app/MainActivity.kt')
history = read('app/src/main/java/com/abfilepro/app/core/ConversionHistoryStore.kt')
office = read('app/src/main/java/com/abfilepro/app/core/OfficeTools.kt')
preview = read('app/src/main/java/com/abfilepro/app/ui/screens/ConversionPreviewDialog.kt')
batch = read('app/src/main/java/com/abfilepro/app/ui/screens/BatchImagePreviewDialog.kt')
checks = {
  'eight_previous_converters': all(x in screen for x in (
      'PdfTools.imagesToPdf(', 'PdfTools.pdfToImages(',
      'OfficeTools.reviewedTextToDocx(', 'OfficeTools.imageTableToXlsx(',
      'OfficeTools.docxToPdf(', 'OfficeTools.xlsxToPdf(',
      'OfficeTools.pdfToDocx(', 'OfficeTools.pdfToXlsx(')),
  'single_file_first_choose': 'quickPick.launch(arrayOf("*/*"))' in screen,
  'language_only_in_ocr_and_saved': screen.index('key = "ocr_header"') < screen.index('ocrMenu = true') < screen.index('key = "image_word"') and 'putString("ocr_language"' in screen,
  'home_and_files_navigation': 'onOpenFiles = { file -> nav.navigate("files?dir=' in main and 'onHome = { nav.navigate("home")' in main,
  'persistent_safe_history': 'getSharedPreferences' in history and 'FileUtils.isInsideRoot(context, it)' in history and 'take(MAX)' in history,
  'no_history_delete': '.delete(' not in history,
  'no_false_save_success': 'files.forEach { FileUtils.confirmSavedFile(context, it) }' in screen and 'savedResults = saved' in screen,
  'share_open_results': 'FileUtils.shareFiles(context, savedResults)' in screen and 'openSaved(savedResults.first())' in screen and 'onOpenPdf = openPdf' in main,
  'preview_save_guard': 'saving: Boolean = false' in preview and 'enabled = !loading && error == null && !saving' in preview,
  'batch_save_guard': 'saving: Boolean = false' in batch and 'enabled = !loading && error == null && !saving' in batch,
  'no_duplicate_run': 'if (!canStart) return' in screen and 'if (saving || busy || drafts.isEmpty()) return' in screen,
  'cancellation_is_not_an_error': 'catch (_: CancellationException)' in screen and 'activeJob?.cancel()' in screen,
  'scroll_position_saved': 'rememberLazyListState()' in screen and 'state = scrollState' in screen,
  'do_not_convert_non_table_into_excel': 'rows.count { row -> row.count { it.isNotBlank() } >= 2 } >= 2' in office,
  'baseline_identity_untouched': 'versionCode = 159' in read('app/build.gradle.kts') and '2.0.0-alpha98-prayer-ui-reference-polish' in read('VERSION'),
}
for label, passed in checks.items():
  print(f"[{'PASS' if passed else 'FAIL'}] {label}")
assert all(checks.values()), 'P5 conversion center static check failed'
print(f"P5 conversion center static: {len(checks)}/{len(checks)} PASS")
