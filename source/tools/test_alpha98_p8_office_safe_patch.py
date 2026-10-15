"""P8 safeguards; static tests don't replace Android functional verification."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]
core=(root/'app/src/main/java/com/abfilepro/app/core/OfficeTools.kt').read_text()
word=(root/'app/src/main/java/com/abfilepro/app/core/SimpleDocxPdfRenderer.kt').read_text()
pdf=(root/'app/src/main/java/com/abfilepro/app/core/PdfTableLayoutExtractor.kt').read_text()
preview=(root/'app/src/main/java/com/abfilepro/app/ui/screens/ConversionPreviewDialog.kt').read_text()
gradle=(root/'app/build.gradle.kts').read_text()
checks={
 'word_uses_layout_renderer':'SimpleDocxPdfRenderer.convert(context, documentXml)' in core,
 'old_xlsx_pdf_unchanged':'renderLinesToPdf(context, lines, "Excel_' in core,
 'pdf_excel_requires_structured_table':'PdfTableLayoutExtractor.extract(context, uri)' in core,
 'pdf_excel_no_prose_fallback':'inferTableRows(text)' not in core[core.index('suspend fun pdfToXlsx'):core.index('fun previewGeneratedOffice')],
 'pdf_no_guess_guard':'لم أجد جدولًا واضحًا' in pdf,
 'pdf_column_alignment':'frequent.size !in 2..12' in pdf,
 'pdf_min_rows':'grid.size < 3' in pdf,
 'pdf_geometry':'p.xDirAdj' in pdf and 'p.widthDirAdj' in pdf,
 'pdf_cancellation':'currentCoroutineContext().ensureActive()' in pdf,
 'pdf_memory_limit':'MAX_GLYPHS_PER_PAGE = 150_000' in pdf,
 'word_margins':'"pgMar"' in word,
 'word_page_size':'"pgSz"' in word,
 'word_paragraph_alignment':'"jc"' in word,
 'word_font_sizes':'"sz", "szCs"' in word,
 'word_explicit_page_break':'pageBreakBefore' in word and "split('\\u000C')" in word,
 'word_no_misleading_complex':'validateSimpleDocx(packageNames, documentXml)' in core,
 'word_cleanup':'result.delete()' in word,
 'word_safe_io':'FileOutputStream(result).use' in word,
 'pdf_zoom_optional':'zoomPdfPreview = !zoomPdfPreview' in preview,
 'new_preview_suffix':'applicationIdSuffix = ".ocrpreviewp8"' in gradle,
 'main_package_unchanged':'applicationId = "com.abfilepro.app"' in gradle,
 'previous_p7_test_kept':(root/'tools/test_alpha98_p7_auto_crop_contract.py').exists(),
}
for name, passed in checks.items(): print(f"[{'PASS' if passed else 'FAIL'}] {name}")
assert all(checks.values()), 'P8 contract failure'
print(f'P8 static office contracts: {len(checks)} PASS')
