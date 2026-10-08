"""Run every JVM test; allow only an identical failure reproduced on original P10."""
import json
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

mode = sys.argv[1]
assert mode in ('baseline', 'patched')
proc = subprocess.run(['gradle', '--no-daemon', '--stacktrace', ':app:testDebugUnitTest'], cwd='project')
reports = Path('project/app/build/test-results/testDebugUnitTest')
results, failures = [], []
for path in sorted(reports.glob('TEST-*.xml')):
    for case in ET.parse(path).getroot().iter('testcase'):
        name = case.attrib['classname'] + '.' + case.attrib['name']
        problem = case.find('failure')
        error = case.find('error')
        skipped = case.find('skipped')
        if problem is None:
            problem = error
        result = {'name': name, 'status': 'FAIL' if problem is not None else 'SKIP' if skipped is not None else 'PASS'}
        if problem is not None:
            result['message'] = problem.attrib.get('message', '')
            result['type'] = problem.attrib.get('type', '')
            failures.append(result)
        results.append(result)
assert len(results) == 29, 'Missing or changed test execution: %s cases' % len(results)
assert not any(r['status'] == 'SKIP' for r in results), results
known = 'com.abfilepro.app.core.WorkspaceShortcutRulesTest.shortcutLabelIsTrimmedAndStable'
assert len(failures) == 1 and failures[0]['name'] == known, failures
assert 'احمد' in failures[0]['message'] and 'إدارة ملفات AB برو' in failures[0]['message'], failures
assert proc.returncode == 1, proc.returncode
summary = {'PASS': 28, 'FAIL': 1, 'results': results}
if mode == 'baseline':
    Path('baseline-jvm-results.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))
    shutil.copytree(reports, 'baseline-unit-reports', dirs_exist_ok=True)
else:
    original = json.loads(Path('baseline-jvm-results.json').read_text())
    assert original == summary, 'P11 introduced a JVM test regression'
    Path('release').mkdir(exist_ok=True)
    Path('release/P11_JVM_BASELINE_COMPARISON.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))
print('P11_JVM_%s: 28 PASS; same pre-existing shortcut-label failure; no skipped tests' % mode.upper(), flush=True)
