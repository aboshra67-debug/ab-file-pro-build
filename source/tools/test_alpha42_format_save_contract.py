from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
s=(ROOT/'app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt').read_text()
g=(ROOT/'app/build.gradle.kts').read_text()
assert 'versionCode = 103' in g and '2.0.0-alpha42-format-save' in g
assert 'outputFormatOverride: ScanOutputFormat? = null' in s
assert 'val requestedOutputFormat = outputFormatOverride ?: singleDocumentOutputFormat' in s
assert 'requestedOutputFormat == ScanOutputFormat.JPG' in s
assert s.count('outputFormatOverride = requestedFormat') >= 2
assert 'ScanProcessor.saveSinglePageJpeg' in s and 'ScanProcessor.createPdf' in s
print('ALPHA42_FORMAT_SAVE_CONTRACT: PASS')
