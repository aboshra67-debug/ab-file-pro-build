"""Release only the exact payload whose native variant passed functional tests."""
import hashlib, json, os, re, shutil, struct, subprocess, zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

phase = Path('p14-ci/phase').read_text().strip()
if phase == 'red':
    print('P13 baseline reproduction only; no new APK release published.', flush=True)
    raise SystemExit(0)
assert phase == 'green', phase
evidence = Path('p14-evidence'); evidence.mkdir(exist_ok=True)
release = Path('p14-release'); release.mkdir(exist_ok=True)
package = 'com.abfilepro.app.p14mainttrial'
version = '2.0.0-alpha98-p14-safe-maintenance'
android = json.loads((evidence / 'green-android-results.json').read_text())
scanner = json.loads(Path('runtime-evidence/runtime_results.json').read_text())
assert android['tests'] == 78 and android['failed'] == 0 and android['gradle_exit'] == 0, android
assert all(r['status'] == 'PASS' for r in android['results']), android
assert len(scanner) == 19 and all(r['status'] == 'PASS' for r in scanner), scanner
apk = Path('project/app/build/outputs/apk/debug/app-debug.apk')

def sha(file):
    digest = hashlib.sha256()
    with file.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()

def payload(file):
    with zipfile.ZipFile(file) as archive:
        assert archive.testzip() is None
        return {n: hashlib.sha256(archive.read(n)).hexdigest() for n in archive.namelist()
                if not n.endswith('/') and not n.startswith(('META-INF/', 'lib/x86_64/'))}

tested = payload(apk); native_test_sha = sha(apk)
with zipfile.ZipFile(apk) as archive:
    assert {n.split('/')[1] for n in archive.namelist() if n.startswith('lib/')} == {'arm64-v8a', 'x86_64'}
# Fresh JVM XML, then default ARM64 rebuild. The shared payload must match.
unit_dir = Path('project/app/build/test-results/testDebugUnitTest')
if unit_dir.exists(): shutil.rmtree(unit_dir)
subprocess.run(['gradle', '--no-daemon', '--stacktrace', ':app:testDebugUnitTest', ':app:assembleDebug'], cwd='project', check=True)
unit = []
for file in sorted(unit_dir.glob('TEST-*.xml')):
    for case in ET.parse(file).getroot().iter('testcase'):
        problem = case.find('failure')
        if problem is None: problem = case.find('error')
        unit.append({'class': case.attrib.get('classname', ''), 'name': case.attrib['name'],
                     'status': 'FAIL' if problem is not None else 'SKIP' if case.find('skipped') is not None else 'PASS'})
assert len(unit) == 29 and all(r['status'] == 'PASS' for r in unit), unit
(evidence / 'jvm-results.json').write_text(json.dumps({'tests': len(unit), 'passed': len(unit), 'failed': 0, 'results': unit}, indent=2))
delivered = payload(apk)
assert tested == delivered, {'added': sorted(set(delivered) - set(tested)), 'missing': sorted(set(tested) - set(delivered)),
                            'changed': [n for n in tested.keys() & delivered.keys() if tested[n] != delivered[n]]}
with zipfile.ZipFile(apk) as archive:
    assert {n.split('/')[1] for n in archive.namelist() if n.startswith('lib/')} == {'arm64-v8a'}
(evidence / 'native-test-to-phone-payload.json').write_text(json.dumps({
    'native_test_apk_sha256': native_test_sha, 'phone_apk_sha256': sha(apk),
    'native_test_abis': ['arm64-v8a', 'x86_64'], 'phone_abis': ['arm64-v8a'],
    'shared_entries_verified': len(tested), 'all_shared_entries_identical': True, 'shared_payload_sha256': delivered}, indent=2))

sdk_tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0'
badging = subprocess.check_output([str(sdk_tools / 'aapt'), 'dump', 'badging', str(apk)], text=True)
assert "name='" + package + "'" in badging and "versionCode='166'" in badging and "versionName='" + version + "'" in badging, badging
assert "application-label:'AB File Pro P14'" in badging, badging
(evidence / 'apk-badging.txt').write_text(badging)
manifest = subprocess.check_output([str(sdk_tools / 'aapt'), 'dump', 'xmltree', str(apk), 'AndroidManifest.xml'], text=True)
assert 'com.abfilepro.app.maintenance.test.documents' not in manifest and 'MaintenanceGrantReceiver' not in manifest
signature = subprocess.check_output([str(sdk_tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
(evidence / 'apk-signature.txt').write_text(signature)
alignment = subprocess.check_output([str(sdk_tools / 'zipalign'), '-c', '-P', '16', '-v', '4', str(apk)], text=True)
(evidence / 'apk-zip-alignment.txt').write_text(alignment)
libraries = []
with zipfile.ZipFile(apk) as archive:
    for name in sorted(n for n in archive.namelist() if n.startswith('lib/arm64-v8a/') and n.endswith('.so')):
        data = archive.read(name); assert data[:6] == b'\x7fELF\x02\x01', name
        phoff = struct.unpack_from('<Q', data, 32)[0]; entsize, count = struct.unpack_from('<HH', data, 54)
        loads = []
        for index in range(count):
            kind, flags, offset, address, physical, file_size, memory_size, align = struct.unpack_from('<IIQQQQQQ', data, phoff + index * entsize)
            if kind == 1:
                assert align >= 16384 and offset % 16384 == address % 16384, (name, offset, address, align)
                loads.append({'offset': offset, 'address': address, 'alignment': align})
        assert loads, name
        libraries.append({'name': name, 'load_segments': loads})
(evidence / 'native-16kb-static-alignment.json').write_text(json.dumps({'libraries': libraries, 'all_load_segments_16kb_aligned': True}, indent=2))

# Runs inside the live emulator on the exact ARM64 APK delivered to the user.
subprocess.run(['adb', 'install', '-r', str(apk)], check=True)
subprocess.run(['adb', 'shell', 'am', 'force-stop', package], check=True)
subprocess.run(['adb', 'logcat', '-b', 'crash', '-c'], check=True)
launch = subprocess.check_output(['adb', 'shell', 'am', 'start', '-W', '-n', package + '/com.abfilepro.app.MainActivity'], text=True)
(evidence / 'startup-launch.txt').write_text(launch); assert 'Status: ok' in launch, launch
def dump_ui():
    subprocess.run(['adb', 'shell', 'uiautomator', 'dump', '/sdcard/p14-startup.xml'], check=True)
    subprocess.run(['adb', 'pull', '/sdcard/p14-startup.xml', str(evidence / 'startup.xml')], check=True)
    return ET.parse(evidence / 'startup.xml')
ui = dump_ui(); dismissed = False
if not any(n.attrib.get('package') == package for n in ui.iter('node')):
    cancel = next((n for n in ui.iter('node') if n.attrib.get('package') == 'com.google.android.apps.nexuslauncher' and n.attrib.get('text') == 'Cancel'), None)
    if cancel is not None:
        (evidence / 'startup-shortcut-dialog.xml').write_bytes((evidence / 'startup.xml').read_bytes())
        bounds = list(map(int, re.findall(r'\d+', cancel.attrib['bounds']))); assert len(bounds) == 4, bounds
        subprocess.run(['adb', 'shell', 'input', 'tap', str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2)], check=True)
        subprocess.run(['adb', 'shell', 'am', 'start', '-W', '-n', package + '/com.abfilepro.app.MainActivity'], check=True)
        ui = dump_ui(); dismissed = True
with (evidence / 'startup.png').open('wb') as output:
    subprocess.run(['adb', 'exec-out', 'screencap', '-p'], stdout=output, check=True)
crash = subprocess.check_output(['adb', 'logcat', '-d', '-b', 'crash'], text=True)
(evidence / 'startup-crash-buffer.txt').write_text(crash)
assert 'Process: ' + package not in crash, crash
assert any(n.attrib.get('package') == package for n in ui.iter('node')), 'Target app UI did not appear'
(evidence / 'startup-summary.json').write_text(json.dumps({'launch_status': 'ok', 'target_ui_visible': True,
    'target_crash': False, 'package': package, 'exact_delivered_apk_sha256': sha(apk),
    'existing_launcher_shortcut_prompt_dismissed': dismissed}, indent=2))
result = release / 'AB98_P14_SAFE_MAINTENANCE_TEST.apk'; shutil.copy2(apk, result)
(release / 'APK_SHA256.txt').write_text(sha(result) + '  ' + result.name + '\n')
summary = {'phase': 'green', 'android_functions': 78, 'scanner_ui_cases': 19, 'jvm_tests': 29, 'all_passed': True,
    'package': package, 'version_code': 166, 'version_name': version, 'apk': result.name,
    'apk_bytes': result.stat().st_size, 'apk_sha256': sha(result), 'commit': os.environ.get('GITHUB_SHA'),
    'run_id': os.environ.get('GITHUB_RUN_ID'),
    'android_api_tested': subprocess.check_output(['adb', 'shell', 'getprop', 'ro.build.version.sdk'], text=True).strip(),
    'phone_abi': 'arm64-v8a', 'physical_phone_tested': False, 'original_signing_key_available': False, 'play_release_ready': False}
(evidence / 'release-verification.json').write_text(json.dumps(summary, indent=2))
(release / 'VERIFICATION.json').write_text(json.dumps(summary, indent=2))
for name in ['apk-badging.txt', 'apk-signature.txt', 'native-test-to-phone-payload.json', 'native-16kb-static-alignment.json', 'startup-summary.json']:
    shutil.copy2(evidence / name, release / name)
print('P14_PHONE_APK_VERIFIED', json.dumps(summary), flush=True)
