from pathlib import Path
s=Path("app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt").read_text()
assert "val requestedMode = selectedMode" in s
assert "requestedOutputFormat == ScanOutputFormat.JPG" in s
assert "ScanProcessor.saveSinglePageJpeg(" in s
assert "ScanProcessor.createPdf(" in s
assert "FileUtils.confirmSavedFile(context, saved)" in s
assert "FileUtils.shareFile(context, file)" in s
print("Alpha43 export/save/share contract PASS")
