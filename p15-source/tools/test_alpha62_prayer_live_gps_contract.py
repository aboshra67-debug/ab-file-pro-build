from pathlib import Path

root=Path(__file__).resolve().parents[1]
ui=(root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantModuleScreens.kt').read_text()
repo=(root/'app/src/main/java/com/abfilepro/app/assistant/AssistantRepository.kt').read_text()
manifest=(root/'app/src/main/AndroidManifest.xml').read_text()
checks={
 'fine_permission':'ACCESS_FINE_LOCATION' in manifest,
 'coarse_permission':'ACCESS_COARSE_LOCATION' in manifest,
 'live_updates':'requestLocationUpdates' in ui,
 'current_location':'getCurrentLocation' in ui,
 'gps_provider':'GPS_PROVIDER' in ui,
 'network_provider':'NETWORK_PROVIDER' in ui,
 'timeout_fallback':'12_000L' in ui,
 'device_id_persist':'if (rawCityId == "device") "device" else city.id' in repo,
 'gps_confirmation':'تم ربط موقعك الحالي' in ui,
}
for k,v in checks.items(): print(('PASS' if v else 'FAIL'), k)
assert all(checks.values())
print(f"PASS={sum(checks.values())} FAIL=0")
