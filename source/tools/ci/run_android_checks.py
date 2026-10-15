import os as _os, sys as _sys
_sys.path.insert(0, _os.path.dirname(_os.path.abspath(__file__)))
CI_HERE = _os.path.dirname(_os.path.abspath(__file__))
import json, subprocess, sys
import xml.etree.ElementTree as ET
from pathlib import Path
import release_config as C

output = Path(C.EVIDENCE); output.mkdir(exist_ok=True)
run = subprocess.run(['gradle', '--no-daemon', '--stacktrace', '-PAB_FILE_PRO_NATIVE_TEST=true',
                      ':app:connectedPlayDebugAndroidTest'], cwd='project')
with (output / 'green-android-logcat.txt').open('w') as log:
    subprocess.run(['adb', 'logcat', '-d', '-v', 'threadtime'], stdout=log, stderr=subprocess.STDOUT)
records = []
for file in sorted(Path('project/app/build/outputs/androidTest-results').rglob('TEST-*.xml')):
    for case in ET.parse(file).getroot().iter('testcase'):
        problem = case.find('failure')
        if problem is None:
            problem = case.find('error')
        records.append({
            'name': case.attrib['name'], 'class': case.attrib.get('classname', ''),
            'status': 'FAIL' if problem is not None else 'SKIP' if case.find('skipped') is not None else 'PASS',
            'details': ((problem.text or '') + '\n' + problem.attrib.get('message', '')) if problem is not None else '',
        })
summary = {'phase': 'green', 'gradle_exit': run.returncode, 'tests': len(records),
           'passed': sum(r['status'] == 'PASS' for r in records),
           'failed': sum(r['status'] == 'FAIL' for r in records), 'results': records}
(output / 'green-android-results.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))
assert len(records) == C.ANDROID_TESTS, summary
assert not any(r['status'] == 'SKIP' for r in records), summary
assert run.returncode == 0 and summary['failed'] == 0, summary
passed = {r['name'] for r in records if r['status'] == 'PASS'}
for release, names in C.REQUIRED_TESTS.items():
    assert names <= passed, (release, sorted(names - passed))
print(C.TAG + '_ANDROID_FUNCTIONS_VERIFIED', len(records), flush=True)
