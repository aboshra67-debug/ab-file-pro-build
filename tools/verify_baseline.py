"""Require the unmodified P10 APK to reproduce both known Scanner failures."""
import json
import os
import subprocess
import sys
from pathlib import Path

env = dict(os.environ, SCANNER_TEST_CASES='capture3_save_restart,gallery_import3_save',
           SCANNER_TEST_PACKAGE='com.abfilepro.app.p10batchtrial')
proc = subprocess.run([sys.executable, '-u', 'tools/scanner_runtime_test.py'], env=env, timeout=600)
results = json.loads(Path('runtime-evidence/runtime_results.json').read_text())
expected = {
    'capture3_save_restart': 'returned to Scanner hub before saving',
    'gallery_import3_save': 'BATCH camera reports 0',
}
assert proc.returncode == 1, 'Baseline unexpectedly passed or failed outside the test assertions'
assert len(results) == len(expected)
for result in results:
    assert result['status'] == 'FAIL', result
    assert expected[result['name']] in result['error'], result
print('P11_BASELINE_VERIFIED: both production defects reproduced on original P10', flush=True)
