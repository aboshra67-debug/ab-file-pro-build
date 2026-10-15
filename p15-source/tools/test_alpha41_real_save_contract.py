from pathlib import Path
root=Path(__file__).resolve().parents[1]
s=(root/'app/src/main/java/com/abfilepro/app/core/ScanProcessor.kt').read_text()
g=(root/'app/build.gradle.kts').read_text()
assert 'versionCode = 103' in g and '2.0.0-alpha42-format-save' in g
assert s.count('output.fd.sync()') >= 2
assert s.count('pending.renameTo(out)') >= 2
assert 'require(targetDir.isDirectory && targetDir.canWrite())' in s
print('PASS Alpha41 real-save durability contract')
