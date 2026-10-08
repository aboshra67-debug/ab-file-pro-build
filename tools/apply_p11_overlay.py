"""Apply a hash-checked P11 overlay to the exact, intact P10 Safe Source."""
import hashlib
import json
import shutil
import zipfile
from pathlib import Path

root = Path('project')
allowed = {
    'app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt',
    'app/build.gradle.kts',
    'VERSION',
}
added = {
    'PATCH_REPORT_AB98_P11_SCANNER_SAFE_FIX_AR.txt',
    'tools/test_alpha98_p11_scanner_runtime.py',
}
digest = lambda data: hashlib.sha256(data).hexdigest()
with zipfile.ZipFile('p10-source.zip') as original:
    entries = {item.filename: original.read(item) for item in original.infolist() if not item.is_dir()}
for name, data in entries.items():
    assert (root / name).read_bytes() == data, 'Baseline differs before patch: ' + name
overlay = Path('p11-overlay')
actual = {str(path.relative_to(overlay)) for path in overlay.rglob('*') if path.is_file()}
assert actual == allowed | added, sorted(actual)
for name in sorted(actual):
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(overlay / name, path)
changes = []
for name, before in entries.items():
    after = (root / name).read_bytes()
    if before != after:
        assert name in allowed, 'Out-of-scope modification: ' + name
        changes.append({'path': name, 'before_sha256': digest(before), 'after_sha256': digest(after)})
assert {item['path'] for item in changes} == allowed, changes
manifest = {
    'baseline_source_sha256': digest(Path('p10-source.zip').read_bytes()),
    'baseline_file_count': len(entries),
    'changed_files': changes,
    'added_files': sorted(added),
    'deleted_files': [],
}
(root / 'P11_SAFE_PATCH_MANIFEST.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps(manifest, indent=2), flush=True)
