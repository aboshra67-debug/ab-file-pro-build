import json,os,subprocess
from pathlib import Path
phase=Path('p15-ci/phase').read_text().strip()
package='com.abfilepro.app.p13sharetrial' if phase=='red' else 'com.abfilepro.app.p15mainttrial'
subprocess.run(['adb','install','-r','project/app/build/outputs/apk/debug/app-debug.apk'],check=True)
for command in [['wm','size','480x1054'],['wm','density','213'],['svc','wifi','disable'],['svc','data','disable']]:
    subprocess.run(['adb','shell',*command],check=True)
env=dict(os.environ,SCANNER_TEST_PACKAGE=package,SCANNER_TEST_SUITE='new' if phase=='red' else 'all')
run=subprocess.run(['python3','-u','p15-ci/scanner_checks.py'],env=env)
records=json.loads(Path('runtime-evidence/runtime_results.json').read_text())
assert len(records)==(5 if phase=='red' else 19),records
assert not any(r['status']=='BLOCKED' for r in records),records
if phase=='red':
    assert run.returncode==1 and any(r['status']=='FAIL' for r in records),records
    print('P14_SCANNER_BASELINE_DEFECTS_REPRODUCED',[(r['name'],r['status']) for r in records],flush=True)
else:
    assert run.returncode==0 and all(r['status']=='PASS' for r in records),records
    print('P15_SCANNER_FUNCTIONS_VERIFIED',len(records),flush=True)
