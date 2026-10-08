"""Confirm two content-preservation defects on the pre-review P11 candidate APK."""
import json
import os
import subprocess
import sys
from pathlib import Path

env = dict(os.environ, SCANNER_TEST_CASES='gallery_bounds_no_change,batch_crop_cancel_keeps_page',
           SCANNER_TEST_PACKAGE='com.abfilepro.app.p11scannertrial')
proc = subprocess.run([sys.executable, '-u', 'tools/p11_ownership_red_test.py'], env=env, timeout=360)
results = json.loads(Path('runtime-evidence/runtime_results.json').read_text())
expected = {'gallery_bounds_no_change': 'discarded image edges',
            'batch_crop_cancel_keeps_page': 'lost the committed page image'}
assert proc.returncode == 1 and len(results) == 2
for result in results:
    assert result['status'] == 'FAIL', result
    assert expected[result['name']] in result['error'], result
print('P11_OWNERSHIP_BASELINE_VERIFIED: crop cancellation and unchanged-bounds defects reproduced', flush=True)
