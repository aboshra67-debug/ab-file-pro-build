from pathlib import Path
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
def read(p): return (ROOT/p).read_text(encoding='utf-8')
def must(text, needle, label):
    assert needle in text, f'missing {label}: {needle}'

camera=read('app/src/main/java/com/abfilepro/app/ui/screens/ScannerProCamera.kt')
scan=read('app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt')
processor=read('app/src/main/java/com/abfilepro/app/core/ScanProcessor.kt')
updater=read('app/src/main/java/com/abfilepro/app/core/AppUpdateManager.kt')
main=read('app/src/main/java/com/abfilepro/app/MainActivity.kt')
gradle=read('app/build.gradle.kts')
manifest=read('app/src/main/AndroidManifest.xml')
core=read('app/src/main/java/com/abfilepro/app/core/OfficeTools.kt')
word=read('app/src/main/java/com/abfilepro/app/core/SimpleDocxPdfRenderer.kt')
pdf=read('app/src/main/java/com/abfilepro/app/core/PdfTableLayoutExtractor.kt')
preview=read('app/src/main/java/com/abfilepro/app/ui/screens/ConversionPreviewDialog.kt')

# P9 scanner safeguards
must(camera,'retireDocumentPreview()','batch preview retirement')
must(camera,'metrics = null','batch stale metrics reset')
must(camera,'ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY','scanner quality capture')
must(camera,'analysisExecutor.execute {','off-main scanner processing')
must(camera,'PreviewView.ScaleType.FIT_CENTER','preview fit mode')
must(processor,'if (preserveSourceDetail)','document detail flag used')
must(processor,'3200','safe detail cap')
must(scan,'fun cancelDocumentProcessing()','processing cancel')

# P10 exact fix
must(scan,'if (requestedMode != ScanCaptureMode.BATCH)','batch must not auto-open saved location')
must(scan,'val restartBatch = selectedMode == ScanCaptureMode.BATCH','batch restart mode')
must(scan,'multiPageCapturedPages = emptyList()','batch state reset')
must(scan,'multiPageReviewIndex = 0','batch review index reset')

# updater retained
must(updater,'verifySha256','update hash verification')
must(updater,'verifyArchiveIdentity','update identity verification')
must(main,'AppUpdateManager.checkForUpdate','startup update check')
must(manifest,'android.permission.REQUEST_INSTALL_PACKAGES','install permission')

# Office P8/P9 functionality retained
checks={
 'word_uses_layout_renderer':'SimpleDocxPdfRenderer.convert(context, documentXml)' in core,
 'old_xlsx_pdf_unchanged':'renderLinesToPdf(context, lines, "Excel_' in core,
 'pdf_excel_requires_structured_table':'PdfTableLayoutExtractor.extract(context, uri)' in core,
 'pdf_excel_no_prose_fallback':'inferTableRows(text)' not in core[core.index('suspend fun pdfToXlsx'):core.index('fun previewGeneratedOffice')],
 'pdf_no_guess_guard':'لم أجد جدولًا واضحًا' in pdf,
 'pdf_geometry':'p.xDirAdj' in pdf and 'p.widthDirAdj' in pdf,
 'word_margins':'"pgMar"' in word,
 'word_page_size':'"pgSz"' in word,
 'word_paragraph_alignment':'"jc"' in word,
 'word_font_sizes':'"sz", "szCs"' in word,
 'word_cleanup':'result.delete()' in word,
 'word_safe_io':'FileOutputStream(result).use' in word,
 'pdf_zoom_optional':'zoomPdfPreview = !zoomPdfPreview' in preview,
}
for n,v in checks.items():
    assert v, n

must(gradle,'versionCode = 162','P10 version code')
must(gradle,'versionName = "2.0.0-alpha98-p10-batch-save-flow"','P10 version name')
must(gradle,'applicationIdSuffix = ".p10batchtrial"','P10 side-by-side package')
ET.parse(ROOT/'app/src/main/AndroidManifest.xml')
print('PASS P10 batch-save scanner regression contract')
