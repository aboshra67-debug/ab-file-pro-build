from pathlib import Path
root=Path(__file__).resolve().parents[1]
module=(root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantModuleScreens.kt').read_text()
meet=(root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantMeetingsScreen.kt').read_text()
store=(root/'app/src/main/java/com/abfilepro/app/assistant/MeetingMinutesStore.kt').read_text()
gradle=(root/'app/build.gradle.kts').read_text()
assert 'AssistantMeetingsScreen(entries, defaultCategory, onBack, onAdd)' in module
assert 'ActivityResultContracts.TakePicture()' in meet
assert 'تصوير المحضر' in meet
assert 'MeetingMinutesStore' in meet
assert 'meeting_minutes' in store
assert 'FileProvider.getUriForFile' in store
assert 'versionCode = 125' in gradle
assert '2.0.0-alpha64-meeting-minutes-camera' in gradle
print('ALPHA64 MEETING MINUTES CAMERA CONTRACT: PASS')
