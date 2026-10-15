import subprocess
from pathlib import Path

phase = Path('p14-ci/phase').read_text().strip()
android = subprocess.run(['python3', 'p14-ci/run_android_checks.py'])
if phase == 'green' and android.returncode:
    raise SystemExit(android.returncode)
scanner = subprocess.run(['python3', 'p14-ci/run_scanner_phase.py'])
assert android.returncode == 0 and scanner.returncode == 0, (android.returncode, scanner.returncode)
