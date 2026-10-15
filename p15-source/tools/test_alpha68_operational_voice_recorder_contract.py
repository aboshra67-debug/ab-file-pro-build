from pathlib import Path
root=Path(__file__).resolve().parents[1]
voice=(root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/OperationalVoiceRecorder.kt').read_text()
manifest=(root/'app/src/main/AndroidManifest.xml').read_text()
gradle=(root/'app/build.gradle.kts').read_text()
checks={
'identity':'versionCode = 129' in gradle and '2.0.0-alpha68-operational-voice-recorder' in gradle,
'permission':'android.permission.RECORD_AUDIO' in manifest,
'recorder':'MediaRecorder' in voice and 'setAudioSource(MediaRecorder.AudioSource.MIC)' in voice,
'pause_resume':'recorder?.pause()' in voice and 'recorder?.resume()' in voice,
'quality':'96_000' in voice and '192_000' in voice,
'save':'FileProvider.getUriForFile' in voice and 'onAdd(title, details' in voice,
'playback':'MediaPlayer()' in voice and 'mp.start()' in voice,
'import':'ActivityResultContracts.OpenDocument()' in voice and 'audio/*' in voice,
'share':'Intent.ACTION_SEND' in voice,
'delete':'deleteOwnRecordingIfPossible' in voice,
'ui':'بدء التسجيل' in voice and 'التسجيلات الأخيرة' in voice and 'جودة التسجيل' in voice,
}
failed=[k for k,v in checks.items() if not v]
for k,v in checks.items(): print(f"[{'PASS' if v else 'FAIL'}] {k}")
if failed: raise SystemExit('Alpha68 contract failed: '+', '.join(failed))
print('Alpha68 Operational Voice Recorder contract: PASS')
