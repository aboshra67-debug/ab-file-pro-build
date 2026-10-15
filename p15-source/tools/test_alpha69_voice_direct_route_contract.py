from pathlib import Path
import re
ROOT = Path(__file__).resolve().parents[1]
module = (ROOT/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantModuleScreens.kt').read_text()
gradle = (ROOT/'app/build.gradle.kts').read_text()
version = (ROOT/'VERSION').read_text().strip()
checks = {
    'identity': version == '2.0.0-alpha69-voice-direct-route' and 'versionCode = 130' in gradle and 'versionName = "2.0.0-alpha69-voice-direct-route"' in gradle,
    'direct_voice_guard': 'if (module == AssistantModule.VOICE)' in module and 'VoiceRecorderScreen(entries, defaultCategory, onBack, onAdd, onDelete)' in module,
    'voice_before_arabic_reference': module.index('if (module == AssistantModule.VOICE)') < module.index('if (language.code == "ar" && !showLegacyInteraction)'),
    'operational_screen_preserved': (ROOT/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/OperationalVoiceRecorder.kt').exists(),
}
for k,v in checks.items(): print(f"[{'PASS' if v else 'FAIL'}] {k}")
if not all(checks.values()): raise SystemExit('Alpha69 voice direct route contract failed')
print('Alpha69 Voice Direct Route contract: PASS')
