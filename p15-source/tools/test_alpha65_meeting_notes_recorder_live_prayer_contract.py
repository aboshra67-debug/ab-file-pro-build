from pathlib import Path
root=Path(__file__).resolve().parents[1]
meeting=(root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantMeetingsScreen.kt').read_text()
store=(root/'app/src/main/java/com/abfilepro/app/assistant/MeetingMinutesStore.kt').read_text()
ref=(root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantReferenceVisuals.kt').read_text()
gradle=(root/'app/build.gradle.kts').read_text()
checks={
'version': '2.0.0-alpha65-meeting-notes-recorder-live-prayer' in gradle,
'version_code': 'versionCode = 126' in gradle,
'meeting_note': 'نوّتة / محضر الاجتماع' in meeting and 'saveNote' in meeting,
'meeting_record': 'بدء تسجيل الاجتماع' in meeting and 'RECORD_SOUND_ACTION' in meeting,
'audio_private_copy': 'importAudio' in store and 'recording_' in store,
'photo_preserved': 'تصوير المحضر' in meeting,
'live_prayer': 'الصلاة القادمة' in ref and 'countdown' in ref and 'settings.cityName' in ref,
'no_static_prayer_header_overlay': 'x = maxWidth * (300f / REF_W)' in ref and 'y = maxHeight * (458f / REF_H)' in ref,
}
for k,v in checks.items(): print(('PASS' if v else 'FAIL'),k)
raise SystemExit(0 if all(checks.values()) else 1)
