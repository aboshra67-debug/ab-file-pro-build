from pathlib import Path
root = Path(__file__).resolve().parents[2]
home = (root/'app/src/main/java/com/abfilepro/app/ui/screens/HomeScreen.kt').read_text(encoding='utf-8')
hub = (root/'app/src/main/java/com/abfilepro/app/ui/screens/SmartScanHub.kt').read_text(encoding='utf-8')
required_home = [
 'ic_ab_files.xml','ic_ab_scanner.xml','ic_ab_convert.xml','ic_ab_assistant.xml',
 'ic_ab_ai.xml','ic_ab_language.xml','ic_ab_settings.xml','ic_ab_help_robot.xml'
]
for name in required_home:
    p = root/'app/src/main/res/drawable'/name
    assert p.exists(), f'missing home visual drawable: {name}'
for ref in ['R.drawable.ic_ab_files','R.drawable.ic_ab_scanner','R.drawable.ic_ab_convert','R.drawable.ic_ab_assistant']:
    assert ref in home, ref
assert 'painterResource(tool.iconRes)' in home
assert 'painterResource(R.drawable.ic_ab_help_robot)' in home

required_scan_images = [
 'scan_mode_document.webp','scan_mode_batch.webp','scan_mode_id.webp',
 'scan_mode_receipt.webp','scan_mode_barcode.webp','scan_mode_translation.webp',
 'scan_mode_images_pdf.webp'
]
for name in required_scan_images:
    p = root/'app/src/main/res/drawable-nodpi'/name
    assert p.exists(), f'missing Alpha11 scan image: {name}'
for ref in [
 'R.drawable.scan_mode_document','R.drawable.scan_mode_batch','R.drawable.scan_mode_id',
 'R.drawable.scan_mode_receipt','R.drawable.scan_mode_barcode','R.drawable.scan_mode_translation',
 'R.drawable.scan_mode_images_pdf'
]:
    assert ref in hub, ref
assert 'painterResource(entry.imageRes)' in hub
print('visual button images contract: PASS')
