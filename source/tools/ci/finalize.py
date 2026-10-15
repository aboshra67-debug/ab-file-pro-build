"""Release only the exact payloads whose native-test variants passed, for both the playDebug
test APK and the R8 playQa APK; verify channel policy (Play has no installer permission) and
package the unsigned Play AAB + R8 mapping for the store upload step."""
import os as _os, sys as _sys
_sys.path.insert(0, _os.path.dirname(_os.path.abspath(__file__)))
CI_HERE = _os.path.dirname(_os.path.abspath(__file__))

import hashlib, json, os, re, shutil, struct, subprocess, sys, zipfile
import xml.etree.ElementTree as ET
from pathlib import Path
import release_config as C

evidence = Path(C.EVIDENCE); evidence.mkdir(exist_ok=True)
release = Path(C.RELEASE); release.mkdir(exist_ok=True)
android = json.loads((evidence / 'green-android-results.json').read_text())
scanner = json.loads(Path('runtime-evidence/runtime_results.json').read_text())
qa = json.loads((evidence / 'qa-smoke.json').read_text())
assert android['tests'] == C.ANDROID_TESTS and android['failed'] == 0 and android['gradle_exit'] == 0, android
assert all(r['status'] == 'PASS' for r in android['results']), android
assert len(scanner) == C.SCANNER_CASES and all(r['status'] == 'PASS' for r in scanner), scanner
assert qa['crash_or_anr'] is False, qa

def sha(file):
    digest = hashlib.sha256()
    with Path(file).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()

def payload(file):
    with zipfile.ZipFile(file) as archive:
        assert archive.testzip() is None
        return {n: hashlib.sha256(archive.read(n)).hexdigest() for n in archive.namelist()
                if not n.endswith('/') and not n.startswith(('META-INF/', 'lib/x86_64/'))}

def abis(file):
    with zipfile.ZipFile(file) as archive:
        return {n.split('/')[1] for n in archive.namelist() if n.startswith('lib/')}

tested = {'debug': payload(C.DEBUG_APK), 'qa': payload(C.QA_APK)}
native_sha = {'debug': sha(C.DEBUG_APK), 'qa': sha(C.QA_APK)}
for path in (C.DEBUG_APK, C.QA_APK):
    assert abis(path) == {'arm64-v8a', 'x86_64'}, path

unit_dir = Path('project/app/build/test-results/testPlayDebugUnitTest')
if unit_dir.exists(): shutil.rmtree(unit_dir)
subprocess.run(['gradle', '--no-daemon', '--stacktrace', ':app:testPlayDebugUnitTest', ':app:assemblePlayDebug',
                ':app:assemblePlayQa', ':app:assembleDirectDebug', ':app:bundlePlayRelease'], cwd='project', check=True)
unit = []
for file in sorted(unit_dir.glob('TEST-*.xml')):
    for case in ET.parse(file).getroot().iter('testcase'):
        problem = case.find('failure')
        if problem is None: problem = case.find('error')
        unit.append({'class': case.attrib.get('classname', ''), 'name': case.attrib['name'],
                     'status': 'FAIL' if problem is not None else 'SKIP' if case.find('skipped') is not None else 'PASS'})
assert len(unit) == C.JVM_TESTS and all(r['status'] == 'PASS' for r in unit), unit
(evidence / 'jvm-results.json').write_text(json.dumps({'tests': len(unit), 'passed': len(unit), 'failed': 0, 'results': unit}, indent=2))

payload_report = {}
for kind, path in (('debug', C.DEBUG_APK), ('qa', C.QA_APK)):
    delivered = payload(path)
    assert tested[kind] == delivered, {kind: {'added': sorted(set(delivered) - set(tested[kind])),
        'missing': sorted(set(tested[kind]) - set(delivered)),
        'changed': [n for n in tested[kind].keys() & delivered.keys() if tested[kind][n] != delivered[n]]}}
    assert abis(path) == {'arm64-v8a'}, path
    payload_report[kind] = {'native_test_apk_sha256': native_sha[kind], 'phone_apk_sha256': sha(path),
                            'shared_entries_verified': len(delivered), 'all_shared_entries_identical': True}
(evidence / 'native-test-to-phone-payload.json').write_text(json.dumps(payload_report, indent=2))

tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0'
def badging(path):
    return subprocess.check_output([str(tools / 'aapt'), 'dump', 'badging', path], text=True)
checks = {}
for kind, path, package, label in (('debug', C.DEBUG_APK, C.TEST_PACKAGE, C.TEST_LABEL),
                                   ('qa', C.QA_APK, C.QA_PACKAGE, C.QA_LABEL)):
    text = badging(path)
    (evidence / f'apk-badging-{kind}.txt').write_text(text)
    assert f"name='{package}'" in text and f"versionCode='{C.VERSION_CODE}'" in text and f"versionName='{C.VERSION_NAME}'" in text, text
    assert f"application-label:'{label}'" in text, text
    assert 'android.permission.REQUEST_INSTALL_PACKAGES' not in text, 'Play channel must not request package installs'
    manifest = subprocess.check_output([str(tools / 'aapt'), 'dump', 'xmltree', path, 'AndroidManifest.xml'], text=True)
    assert 'com.abfilepro.app.maintenance.test.documents' not in manifest and 'MaintenanceGrantReceiver' not in manifest
    if kind == 'qa':
        assert 'application-debuggable' not in text, 'QA must be the non-debuggable release code'
    signature = subprocess.check_output([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', path], text=True)
    (evidence / f'apk-signature-{kind}.txt').write_text(signature)
    alignment = subprocess.check_output([str(tools / 'zipalign'), '-c', '-P', '16', '-v', '4', path], text=True)
    (evidence / f'apk-zip-alignment-{kind}.txt').write_text(alignment)
    checks[kind] = {'package': package, 'label': label, 'install_permission_absent': True}
direct = badging(C.DIRECT_APK)
(evidence / 'apk-badging-direct.txt').write_text(direct)
assert 'android.permission.REQUEST_INSTALL_PACKAGES' in direct, 'direct channel keeps its verified updater'
checks['direct'] = {'install_permission_present': True}

libraries = []
with zipfile.ZipFile(C.QA_APK) as archive:
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

def startup(path, package, tag):
    subprocess.run(['adb', 'install', '-r', path], check=True)
    subprocess.run(['adb', 'shell', 'am', 'force-stop', package], check=True)
    subprocess.run(['adb', 'logcat', '-b', 'crash', '-c'], check=True)
    launch = subprocess.check_output(['adb', 'shell', 'am', 'start', '-W', '-n', package + '/com.abfilepro.app.MainActivity'], text=True)
    (evidence / f'startup-launch-{tag}.txt').write_text(launch); assert 'Status: ok' in launch, launch
    def dump_ui():
        subprocess.run(['adb', 'shell', 'uiautomator', 'dump', f'/sdcard/{C.TAG.lower()}-startup-{tag}.xml'], check=True)
        subprocess.run(['adb', 'pull', f'/sdcard/{C.TAG.lower()}-startup-{tag}.xml', str(evidence / f'startup-{tag}.xml')], check=True)
        return ET.parse(evidence / f'startup-{tag}.xml')
    ui = dump_ui()
    if not any(n.attrib.get('package') == package for n in ui.iter('node')):
        cancel = next((n for n in ui.iter('node') if n.attrib.get('package') == 'com.google.android.apps.nexuslauncher' and n.attrib.get('text') == 'Cancel'), None)
        if cancel is not None:
            bounds = list(map(int, re.findall(r'\d+', cancel.attrib['bounds'])))
            subprocess.run(['adb', 'shell', 'input', 'tap', str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2)], check=True)
            subprocess.run(['adb', 'shell', 'am', 'start', '-W', '-n', package + '/com.abfilepro.app.MainActivity'], check=True)
            ui = dump_ui()
    with (evidence / f'startup-{tag}.png').open('wb') as output:
        subprocess.run(['adb', 'exec-out', 'screencap', '-p'], stdout=output, check=True)
    crash = subprocess.check_output(['adb', 'logcat', '-d', '-b', 'crash'], text=True)
    (evidence / f'startup-crash-buffer-{tag}.txt').write_text(crash)
    assert 'Process: ' + package not in crash, crash
    assert any(n.attrib.get('package') == package for n in ui.iter('node')), f'{tag}: target UI did not appear'
    return {'launch_status': 'ok', 'target_ui_visible': True, 'target_crash': False, 'package': package, 'apk_sha256': sha(path)}

startups = {'debug': startup(C.DEBUG_APK, C.TEST_PACKAGE, 'debug'), 'qa': startup(C.QA_APK, C.QA_PACKAGE, 'qa')}
(evidence / 'startup-summary.json').write_text(json.dumps(startups, indent=2))

outputs = {f'AB98_{C.TAG}_PLAY_TEST.apk': C.DEBUG_APK, f'AB98_{C.TAG}_PLAY_QA_R8.apk': C.QA_APK,
           f'AB98_{C.TAG}_PLAY_RELEASE_UNSIGNED.aab': C.AAB, f'AB98_{C.TAG}_PLAY_RELEASE_mapping.txt': C.MAPPING}
hashes = {}
for name, source in outputs.items():
    assert Path(source).is_file(), source
    shutil.copy2(source, release / name); hashes[name] = sha(release / name)
(release / 'SHA256SUMS.txt').write_text(''.join(f'{h}  {n}\n' for n, h in hashes.items()))
summary = {'phase': 'green', 'android_functions': C.ANDROID_TESTS, 'scanner_ui_cases': C.SCANNER_CASES,
    'jvm_tests': C.JVM_TESTS, 'qa_r8_monkey_events': qa['monkey_events'], 'all_passed': True,
    'version_code': C.VERSION_CODE, 'version_name': C.VERSION_NAME, 'channel_checks': checks,
    'outputs': {n: {'bytes': (release / n).stat().st_size, 'sha256': h} for n, h in hashes.items()},
    'commit': os.environ.get('GITHUB_SHA'), 'run_id': os.environ.get('GITHUB_RUN_ID'),
    'android_api_tested': subprocess.check_output(['adb', 'shell', 'getprop', 'ro.build.version.sdk'], text=True).strip(),
    'phone_abi': 'arm64-v8a', 'physical_phone_tested': False, 'aab_signed': False,
    'original_signing_key_available': False, 'play_release_ready': False}
(evidence / 'release-verification.json').write_text(json.dumps(summary, indent=2))
(release / 'VERIFICATION.json').write_text(json.dumps(summary, indent=2))
for name in ['native-test-to-phone-payload.json', 'native-16kb-static-alignment.json', 'startup-summary.json',
             'apk-badging-debug.txt', 'apk-badging-qa.txt', 'apk-signature-debug.txt', 'apk-signature-qa.txt', 'qa-smoke.json']:
    shutil.copy2(evidence / name, release / name)
print(C.TAG + '_RELEASE_VERIFIED', json.dumps(summary), flush=True)
