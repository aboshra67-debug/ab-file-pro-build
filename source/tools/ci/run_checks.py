import os, subprocess, sys
here = os.path.dirname(os.path.abspath(__file__))
for step in ('run_android_checks.py', 'run_scanner_phase.py', 'qa_smoke.py'):
    result = subprocess.run([sys.executable, os.path.join(here, step)])
    if result.returncode:
        raise SystemExit(result.returncode)
