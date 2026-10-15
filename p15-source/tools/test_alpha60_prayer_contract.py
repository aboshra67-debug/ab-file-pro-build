from pathlib import Path
root=Path(__file__).resolve().parents[1]
checks={
"version": "2.0.0-alpha60-prayer-gps-adhan-check" in (root/"VERSION").read_text(),
"voice_enum": "enum class AdhanVoice" in (root/"app/src/main/java/com/abfilepro/app/assistant/AssistantModels.kt").read_text(),
"gps": "refreshPrayerGpsLocation" in (root/"app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantModuleScreens.kt").read_text(),
"minshawi": "MINSHAWI" in (root/"app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantModuleScreens.kt").read_text(),
"abdulbasit": "ABDUL_BASIT" in (root/"app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantModuleScreens.kt").read_text(),
"question": "هل صليت" in (root/"app/src/main/java/com/abfilepro/app/assistant/PrayerAlarmReceiver.kt").read_text(),
"response_receiver": (root/"app/src/main/java/com/abfilepro/app/assistant/PrayerResponseReceiver.kt").exists(),
"completion_store": (root/"app/src/main/java/com/abfilepro/app/assistant/PrayerCompletionStore.kt").exists(),
"manifest_receiver": "PrayerResponseReceiver" in (root/"app/src/main/AndroidManifest.xml").read_text(),
}
failed=[k for k,v in checks.items() if not v]
print(f"Alpha60 contract: {len(checks)-len(failed)}/{len(checks)} PASS")
if failed: raise SystemExit("FAILED: "+", ".join(failed))
