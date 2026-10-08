"""Package exactly the source that was built, preserving every baseline file."""
import difflib
import hashlib
import json
import shutil
import zipfile
from pathlib import Path

root = Path('project')
release = Path('release')
release.mkdir(exist_ok=True)
manifest = json.loads((root / 'P11_SAFE_PATCH_MANIFEST.json').read_text())
allowed = {item['path'] for item in manifest['changed_files']}
diff = []
with zipfile.ZipFile('p10-source.zip') as original:
    entries = [item for item in original.infolist() if not item.is_dir()]
    for entry in entries:
        before = original.read(entry)
        after = (root / entry.filename).read_bytes()
        if before != after:
            assert entry.filename in allowed, entry.filename
            diff += list(difflib.unified_diff(before.decode().splitlines(True), after.decode().splitlines(True),
                                            fromfile='P10/' + entry.filename, tofile='P11/' + entry.filename))
    names = [item.filename for item in entries] + manifest['added_files'] + ['P11_SAFE_PATCH_MANIFEST.json']
    with zipfile.ZipFile(release / 'AB_FILE_PRO_V2_ALPHA98_P11_SCANNER_SAFE_FIX_SOURCE.zip', 'w', zipfile.ZIP_DEFLATED) as safe:
        for name in sorted(names):
            safe.write(root / name, name)
(release / 'P11_SAFE_PATCH.diff').write_text(''.join(diff))
shutil.copyfile(root / 'P11_SAFE_PATCH_MANIFEST.json', release / 'P11_SAFE_PATCH_MANIFEST.json')
shutil.copyfile(root / 'PATCH_REPORT_AB98_P11_SCANNER_SAFE_FIX_AR.txt', release / 'PATCH_REPORT_AB98_P11_SCANNER_SAFE_FIX_AR.txt')
files = sorted(release.glob('*.apk')) + sorted(release.glob('*SOURCE.zip'))
(release / 'SHA256SUMS.txt').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + p.name + '\n' for p in files))
print('Safe Source packaged: %s baseline files, no deletions, %s modified files' % (len(entries), len(allowed)), flush=True)
