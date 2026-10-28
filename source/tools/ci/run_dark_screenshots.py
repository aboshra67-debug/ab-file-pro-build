import os as _os, sys as _sys
_sys.path.insert(0, _os.path.dirname(_os.path.abspath(__file__)))
import json, subprocess
from pathlib import Path
import release_config as C

# P28: renders every assistant screen in dark mode (Arabic and English) on the emulator and keeps
# the screenshots as evidence. It is a separate Gradle run so a problem in a screen can never
# affect the 129-test regression result; this script never fails the build.
out = Path(C.EVIDENCE) / 'dark-shots'
out.mkdir(parents=True, exist_ok=True)
run = subprocess.run(['gradle', '--no-daemon', '--stacktrace', '-PAB_FILE_PRO_NATIVE_TEST=true', '-PAB_DARK_SHOTS=true',
                      '-Pandroid.testInstrumentationRunnerArguments.class=com.abfilepro.app.shots.DarkModeScreenshotTest',
                      ':app:connectedPlayDebugAndroidTest'], cwd='project')
subprocess.run(['adb', 'pull', '/data/local/tmp/ab_dark/.', str(out)])
log = subprocess.run(['adb', 'logcat', '-d', '-v', 'brief', '-s', 'DARKSHOT:I', 'AndroidRuntime:E'], capture_output=True, text=True).stdout
(out / 'metrics.txt').write_text(log)
(out / 'run.json').write_text(json.dumps({'gradle_exit': run.returncode, 'files': sorted(p.name for p in out.glob('*.png'))}, indent=2))
print(C.TAG + '_DARK_SHOTS', run.returncode, len(list(out.glob('*.png'))), flush=True)
