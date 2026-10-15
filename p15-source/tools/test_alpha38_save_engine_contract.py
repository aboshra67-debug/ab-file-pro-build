from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
sp = (ROOT / "app/src/main/java/com/abfilepro/app/core/ScanProcessor.kt").read_text()
bg = (ROOT / "app/build.gradle.kts").read_text()
assert 'versionCode = 103' in bg
assert '2.0.0-alpha42-format-save' in bg
# Save engine must not use full-detail 4608px decode for final PDF/JPG exports.
create_pdf = sp[sp.index('suspend fun createPdf'):sp.index('suspend fun exportEditedPageToCache')]
jpg = sp[sp.index('suspend fun saveSinglePageJpeg'):sp.index('suspend fun savePageImages')]
assert 'qualityToMaxDimension(quality)' in create_pdf
assert 'exportMaxDimension(draft.file)' not in create_pdf
assert 'qualityToMaxDimension(quality)' in jpg
assert 'exportMaxDimension(page.file)' not in jpg
print('ALPHA38_SAVE_ENGINE_CONTRACT: PASS')
