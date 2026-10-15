import json, os, subprocess, sys
from pathlib import Path
sys.path.insert(0, 'p16-ci')
import release_config as C

subprocess.run(['adb', 'install', '-r', C.DEBUG_APK], check=True)
for command in [['wm', 'size', '480x1054'], ['wm', 'density', '213'], ['svc', 'wifi', 'disable'], ['svc', 'data', 'disable']]:
    subprocess.run(['adb', 'shell', *command], check=True)
env = dict(os.environ, SCANNER_TEST_PACKAGE=C.TEST_PACKAGE, SCANNER_TEST_SUITE='all')
run = subprocess.run(['python3', '-u', 'p16-ci/scanner_checks.py'], env=env)
records = json.loads(Path('runtime-evidence/runtime_results.json').read_text())
assert len(records) == C.SCANNER_CASES, records
assert not any(r['status'] == 'BLOCKED' for r in records), records
assert run.returncode == 0 and all(r['status'] == 'PASS' for r in records), records
print('P16_SCANNER_FUNCTIONS_VERIFIED', len(records), flush=True)
