from pathlib import Path

root=Path(__file__).resolve().parents[1]
mod=(root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantModuleScreens.kt').read_text()
assert 'if (module == AssistantModule.PRAYER)' in mod
assert 'PrayerModuleScreen(prayerSettings, onBack, onPrayerSettings)' in mod
assert 'Settings.ACTION_LOCATION_SOURCE_SETTINGS' in mod
assert 'استخدم موقعي' in mod
assert 'refreshPrayerGpsLocation' in mod
assert (root/'VERSION').read_text().strip() == '2.0.0-alpha61-prayer-gps-routing-fix'
print('Alpha61 prayer GPS routing contract: PASS 6/6')
