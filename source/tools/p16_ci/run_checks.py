import subprocess
android = subprocess.run(['python3', 'p16-ci/run_android_checks.py'])
if android.returncode:
    raise SystemExit(android.returncode)
scanner = subprocess.run(['python3', 'p16-ci/run_scanner_phase.py'])
if scanner.returncode:
    raise SystemExit(scanner.returncode)
qa = subprocess.run(['python3', 'p16-ci/qa_smoke.py'])
raise SystemExit(qa.returncode)
