from pathlib import Path
root = Path(__file__).resolve().parents[2]
barcode = (root/'app/src/main/java/com/abfilepro/app/ui/screens/BarcodeScreen.kt').read_text(encoding='utf-8')
translation = (root/'app/src/main/java/com/abfilepro/app/core/TranslationProcessor.kt').read_text(encoding='utf-8')
# Keep onBack as the final lambda parameter so the protected MainActivity's BarcodeScreen { ... } call remains source-compatible.
assert 'fun BarcodeScreen(readerOnly: Boolean = false, onBack: () -> Unit)' in barcode
# ML Kit download conditions live in common.model, not the translate package.
assert 'import com.google.mlkit.common.model.DownloadConditions' in translation
assert 'import com.google.mlkit.nl.translate.DownloadConditions' not in translation
print('alpha11 compile regression contract: PASS')
