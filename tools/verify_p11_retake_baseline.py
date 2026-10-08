"""Require the first P11 patch APK to reproduce the remaining retake count defect."""
import json
import os
import subprocess
import sys
from pathlib import Path

env = dict(os.environ, SCANNER_TEST_CASES='batch_retake_replace',
           SCANNER_TEST_PACKAGE='com.abfilepro.app.p11scannertrial')
proc = subprocess.run([sys.executable, '-u', 'tools/p11_retake_red_test.py'], env=env, timeout=300)
results = json.loads(Path('runtime-evidence/runtime_results.json').read_text())
assert proc.returncode == 1 and len(results) == 1
assert results[0]['status'] == 'FAIL', results
assert 'camera now reports 3 pages' in results[0]['error'], results
print('P11_RETAKE_BASELINE_VERIFIED: retake incorrectly appends a third page', flush=True)
