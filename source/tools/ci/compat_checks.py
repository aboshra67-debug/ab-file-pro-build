"""P21: run the instrumented suite and an R8 QA launch/monkey check on another Android version.

Usage: python3 compat_checks.py <api-level>. Writes compat-<api>/ with results, screenshots and the
crash buffer. Fails when an instrumented test fails or the QA build crashes.
"""
import json, os, subprocess, sys, time
import xml.etree.ElementTree as ET
from pathlib import Path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import release_config as C

api = sys.argv[1]
out = Path(f'compat-{api}'); out.mkdir(exist_ok=True)
def adb(*args, check=False):
    return subprocess.run(['adb', *args], check=check, text=True, capture_output=True).stdout

run = subprocess.run(['gradle', '--no-daemon', '--stacktrace', '-PAB_FILE_PRO_NATIVE_TEST=true', ':app:connectedPlayDebugAndroidTest'], cwd='project')
records = []
for file in sorted(Path('project/app/build/outputs/androidTest-results').rglob('TEST-*.xml')):
    for case in ET.parse(file).getroot().iter('testcase'):
        problem = case.find('failure')
        if problem is None: problem = case.find('error')
        records.append({'name': case.attrib['name'], 'class': case.attrib.get('classname', ''),
                        'status': 'FAIL' if problem is not None else 'SKIP' if case.find('skipped') is not None else 'PASS',
                        'details': (problem.text or '')[:4000] if problem is not None else ''})
(out / 'android-results.json').write_text(json.dumps({'api': api, 'gradle_exit': run.returncode, 'tests': len(records),
    'failed': sum(r['status'] == 'FAIL' for r in records), 'results': records}, ensure_ascii=False, indent=2))

adb('install', '-r', C.QA_APK, check=True)
for perm in ['android.permission.CAMERA', 'android.permission.POST_NOTIFICATIONS']:
    adb('shell', 'pm', 'grant', C.QA_PACKAGE, perm)
adb('shell', 'appops', 'set', C.QA_PACKAGE, 'MANAGE_EXTERNAL_STORAGE', 'allow')
adb('logcat', '-b', 'crash', '-c')
launch = adb('shell', 'am', 'start', '-W', '-n', C.QA_PACKAGE + '/com.abfilepro.app.MainActivity')
(out / 'qa-launch.txt').write_text(launch)
time.sleep(6)
with (out / 'qa-start.png').open('wb') as f:
    subprocess.run(['adb', 'exec-out', 'screencap', '-p'], stdout=f)
adb('shell', 'input', 'tap', '100', '120')  # dismiss the intro's skip area if shown
time.sleep(2)
with (out / 'qa-home.png').open('wb') as f:
    subprocess.run(['adb', 'exec-out', 'screencap', '-p'], stdout=f)
monkey = subprocess.run(['adb', 'shell', 'monkey', '-p', C.QA_PACKAGE, '-s', str(2100 + int(api)), '--throttle', '250',
                         '--pct-syskeys', '0', '--pct-appswitch', '0', '--pct-anyevent', '0', '-v', '800'], text=True, capture_output=True)
(out / 'qa-monkey.txt').write_text(monkey.stdout + monkey.stderr)
with (out / 'qa-after-monkey.png').open('wb') as f:
    subprocess.run(['adb', 'exec-out', 'screencap', '-p'], stdout=f)
crash = adb('logcat', '-d', '-b', 'crash')
(out / 'qa-crash-buffer.txt').write_text(crash)
qa_failed = ('Process: ' + C.QA_PACKAGE) in crash or ('// CRASH: ' + C.QA_PACKAGE) in monkey.stdout or ('// NOT RESPONDING: ' + C.QA_PACKAGE) in monkey.stdout
summary = {'api': api, 'android_tests': len(records), 'android_failed': sum(r['status'] == 'FAIL' for r in records),
           'gradle_exit': run.returncode, 'qa_launch_ok': 'Status: ok' in launch, 'qa_crash_or_anr': qa_failed}
(out / 'summary.json').write_text(json.dumps(summary, indent=2))
print('COMPAT_SUMMARY', json.dumps(summary), flush=True)
assert len(records) == C.ANDROID_TESTS, summary
assert run.returncode == 0 and summary['android_failed'] == 0, summary
assert summary['qa_launch_ok'] and not qa_failed, summary
