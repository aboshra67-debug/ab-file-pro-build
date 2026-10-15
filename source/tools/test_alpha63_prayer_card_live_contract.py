from pathlib import Path
root = Path(__file__).resolve().parents[1]
checks = {
    'version': '2.0.0-alpha63-prayer-card-live-countdown' in (root/'VERSION').read_text(),
    'version_code': 'versionCode = 124' in (root/'app/build.gradle.kts').read_text(),
    'city_name_model': 'val cityName: String' in (root/'app/src/main/java/com/abfilepro/app/assistant/AssistantModels.kt').read_text(),
    'city_name_store': 'put("cityName", settings.cityName)' in (root/'app/src/main/java/com/abfilepro/app/assistant/AssistantRepository.kt').read_text(),
    'reverse_geocode': 'resolvePrayerCityName' in (root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantModuleScreens.kt').read_text(),
    'live_countdown': 'delay(1_000)' in (root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantReferenceVisuals.kt').read_text(),
    'reference_image_preserved': 'R.drawable.pa_ref_home_top' in (root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantReferenceVisuals.kt').read_text(),
    'no_sunrise_in_next_prayer': 'listOf(PrayerName.FAJR, PrayerName.DHUHR, PrayerName.ASR, PrayerName.MAGHRIB, PrayerName.ISHA)' in (root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantReferenceVisuals.kt').read_text(),
}
for k,v in checks.items():
    print(('PASS' if v else 'FAIL'), k)
if not all(checks.values()):
    raise SystemExit(1)
