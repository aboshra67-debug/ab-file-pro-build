"""Exercise the R8-optimized playQa build (release code, debug-signed) for crashes.

Launch + a seeded, package-restricted monkey run. Any crash or ANR of the QA package fails.
"""
import json, subprocess, sys
from pathlib import Path
sys.path.insert(0, 'p16-ci')
import release_config as C

out = Path(C.EVIDENCE); out.mkdir(exist_ok=True)
def adb(*args, check=True):
    return subprocess.run(['adb', *args], check=check, text=True, capture_output=True).stdout
adb('install', '-r', C.QA_APK)
for perm in ['android.permission.CAMERA', 'android.permission.POST_NOTIFICATIONS']:
    adb('shell', 'pm', 'grant', C.QA_PACKAGE, perm, check=False)
adb('shell', 'appops', 'set', C.QA_PACKAGE, 'MANAGE_EXTERNAL_STORAGE', 'allow', check=False)
adb('logcat', '-b', 'crash', '-c')
launch = adb('shell', 'am', 'start', '-W', '-n', C.QA_PACKAGE + '/com.abfilepro.app.MainActivity')
(out / 'qa-launch.txt').write_text(launch)
assert 'Status: ok' in launch, launch
monkey = subprocess.run(['adb', 'shell', 'monkey', '-p', C.QA_PACKAGE, '-s', '16016', '--throttle', '250',
                         '--pct-syskeys', '0', '--pct-appswitch', '0', '--pct-anyevent', '0',
                         '--monitor-native-crashes', '-v', '-v', '1500'], text=True, capture_output=True)
(out / 'qa-monkey.txt').write_text(monkey.stdout + '\n' + monkey.stderr)
crash = adb('logcat', '-d', '-b', 'crash')
(out / 'qa-crash-buffer.txt').write_text(crash)
failed = ('Process: ' + C.QA_PACKAGE) in crash or '// CRASH: ' + C.QA_PACKAGE in monkey.stdout \
    or '// NOT RESPONDING: ' + C.QA_PACKAGE in monkey.stdout
summary = {'package': C.QA_PACKAGE, 'launch': 'ok', 'monkey_events': 1500, 'monkey_seed': 16016,
           'monkey_exit': monkey.returncode, 'crash_or_anr': failed}
(out / 'qa-smoke.json').write_text(json.dumps(summary, indent=2))
assert not failed, crash[-4000:] + monkey.stdout[-4000:]
print('P16_QA_R8_SMOKE_VERIFIED', json.dumps(summary), flush=True)
