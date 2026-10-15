from pathlib import Path
root=Path(__file__).resolve().parents[1]
ui=(root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantReferenceVisuals.kt').read_text(encoding='utf-8')
gradle=(root/'app/build.gradle.kts').read_text(encoding='utf-8')
version=(root/'VERSION').read_text(encoding='utf-8').strip()
checks=[
 ('version', version=='2.0.0-alpha94-summary-card-polish'),
 ('versionCode', 'versionCode = 155' in gradle),
 ('versionName', 'versionName = "2.0.0-alpha94-summary-card-polish"' in gradle),
 ('vertical metric count', 'fontSize = 19.sp' in ui and 'text = count.toString()' in ui),
 ('metric label style', 'fontSize = 10.sp' in ui and 'text = label' in ui),
 ('date chip', 'width = maxWidth * (196f / REF_W)' in ui and 'height = maxHeight * (36f / activeRefHeight)' in ui),
 ('prayer preserved', 'PrayerHomeLiveOverlay(' in ui),
 ('filters preserved', 'ClassicCategoryStrip(' in ui),
 ('home preserved', 'ClassicHomeButton(' in ui),
 ('no-next preserved', 'pa_ref_home_top_no_next' in ui),
]
failed=[name for name,ok in checks if not ok]
if failed: raise SystemExit('FAIL: '+', '.join(failed))
print(f'{len(checks)}/{len(checks)} PASS')
