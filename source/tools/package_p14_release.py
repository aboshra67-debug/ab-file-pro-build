"""Package the complete Safe Source only after verified P14 CI artifacts exist."""
import argparse
import hashlib
import json
import shutil
import zipfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--workspace', required=True, type=Path)
parser.add_argument('--evidence', required=True, type=Path)
parser.add_argument('--apk-artifact', required=True, type=Path)
args = parser.parse_args()
task = args.workspace.resolve()
root = task / 'project'
output = task / 'release'
output.mkdir(exist_ok=True)
evidence = args.evidence.resolve()
artifact = args.apk_artifact.resolve()
state = json.loads((task / 'GIT_STATE.json').read_text())
baseline = json.loads((task / 'P13_BASELINE.json').read_text())

def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()

def find(folder, name):
    matches = list(folder.rglob(name))
    assert len(matches) == 1, (name, matches)
    return matches[0]

android = json.loads(find(evidence, 'green-android-results.json').read_text())
scanner = json.loads(find(evidence, 'runtime_results.json').read_text())
jvm = json.loads(find(evidence, 'jvm-results.json').read_text())
verified = json.loads(find(evidence, 'release-verification.json').read_text())
assert android['tests'] == android['passed'] == 79 and android['failed'] == 0 and android['gradle_exit'] == 0
assert len(scanner) == 19 and all(r['status'] == 'PASS' for r in scanner)
assert jvm['tests'] == jvm['passed'] == 29 and jvm['failed'] == 0
assert verified['all_passed'] and verified['commit'] == state['head'] and verified['version_code'] == 166
phone = find(artifact, 'AB98_P14_SAFE_MAINTENANCE_TEST.apk')
assert sha(phone) == verified['apk_sha256']
with zipfile.ZipFile(phone) as archive:
    assert archive.testzip() is None
    assert {n.split('/')[1] for n in archive.namelist() if n.startswith('lib/')} == {'arm64-v8a'}
proof = json.loads(find(evidence, 'native-test-to-phone-payload.json').read_text())
assert proof['all_shared_entries_identical'] and proof['phone_apk_sha256'] == sha(phone)
for row in (task / 'ci/p14-inputs.sha256').read_text().splitlines():
    expected, name = row.split('  ', 1)
    assert sha(root / name) == expected, name
assert all((root / name).is_file() for name in baseline['files'])
changed = sorted(name for name, digest in baseline['files'].items() if sha(root / name) != digest)
assert len(changed) == 15, changed

verification = root / 'P14_VERIFICATION'
verification.mkdir(exist_ok=True)
for name in ['green-android-results.json', 'jvm-results.json', 'runtime_results.json', 'scanner_summary.json',
             'release-verification.json', 'native-test-to-phone-payload.json', 'native-16kb-static-alignment.json',
             'apk-badging.txt', 'apk-signature.txt', 'apk-zip-alignment.txt', 'startup-summary.json', 'startup-launch.txt', 'startup.png']:
    shutil.copy2(find(evidence, name), verification / name)
shutil.copy2(task / 'ci/p14-inputs.sha256', verification / 'p14-compiled-inputs.sha256')
assessment_path = task.parent / 'p13_audit/evaluation_data.json'
assessment = json.loads(assessment_path.read_text())
closed_ids = ['ST-04', 'ST-13', 'ST-N01', 'ST-N02', 'ST-N03', 'ST-N04',
              'DOC-03', 'DOC-04', 'DOC-12', 'DOC-N01', 'SCAN-01', 'SCAN-05', 'SCAN-07', 'SCAN-N01']
all_findings = assessment['finding_updates'] + assessment['new_findings']
assert set(closed_ids) <= {r['id'] for r in all_findings}
delta = {'baseline': 'P13 comprehensive assessment', 'ci_commit': verified['commit'],
         'closed_with_p14_checks': [{'id': r['id'], 'title': r['title']} for r in all_findings if r['id'] in closed_ids],
         'other_findings_require_separate_assessment': [{'id': r['id'], 'title': r['title'], 'p13_status': r['status']} for r in all_findings if r['id'] not in closed_ids],
         'new_program_wide_score': None, 'play_release_ready': False}
(verification / 'P14_FINDINGS_DELTA.json').write_text(json.dumps(delta, ensure_ascii=False, indent=2), encoding='utf-8')
shutil.copy2(assessment_path, verification / 'P13_BASELINE_ASSESSMENT.json')

report = f'''# AB File Pro V2 — AB98 P14

إصدار صيانة مبني على P13 المعتمد. Version Code: 166.

## التعديلات

- Scanner: الحفاظ على وجهي البطاقة وصفحات Batch عند إلغاء إعادة التصوير، واستبدال الصفحة في موضعها نفسه بعد التحقق من الصورة الجديدة؛ يشمل مسار الماسح البديل.
- Scanner: فتح حدود الصفحة المعالجة عند حوافها الحالية، ومنع تكرار قص الحواف دون تعديل؛ التحقق من الهندسة ورفض الصور غير المقروءة.
- الملفات: التحقق من هوية ومحتوى ناتج النقل والتراجع وفك ZIP؛ الاحتفاظ بتعديلات المستخدم والنسخة المكتملة عند فشل حذف المصدر، ورفض قائمة المجلد غير المقروءة.
- SAF: إثبات محتويات المصدر والوجهة قبل الحذف، وحماية الملفات المعدلة أو المضافة أثناء النقل.
- PDF: إصلاح تحويل الصفحات لصورة طويلة، وحساب ميزانية الذاكرة قبل الحجز، ومنع نشر PDF يفتقد صورة مختارة غير مقروءة.
- قراءة الفواتير والبطاقات: الحفاظ على الفواصل العشرية العربية وأسماء جهات الاتصال عند غياب بعض الحقول، وإصلاح نمط قراءة رابط الموقع.

## التحقق

79 اختبار Android وظيفيًا + 19 سيناريو Scanner عبر الواجهة + 29 اختبار JVM: جميعها ناجحة.
Android API المختبر: {verified['android_api_tested']}. الاختبارات نُفذت على محاكي؛ لم يُختبر على هاتف فعلي في هذه الجلسة.
تمت مقارنة جميع عناصر APK المشتركة بين نسخة الاختبار ذات المكتبات الأصلية للمحاكي ونسخة ARM64، ثم تثبيت وتشغيل ملف الهاتف نفسه.
بيانات وسجلات التحقق داخل P14_VERIFICATION.
تشغيل GitHub: https://github.com/aboshra67-debug/ab-file-pro-build/actions/runs/{verified['run_id']}
Commit: {verified['commit']}

## التثبيت والبناء

APK تجريبي مستقل باسم AB File Pro P14، Package: com.abfilepro.app.p14mainttrial.
يُثبت بجوار التطبيقات السابقة. مفاتيح التوقيع الأصلية غير متاحة ولم تُنشأ هوية بديلة للتطبيق الأصلي.
هذه نسخة اختبار للصيانة، وليست حزمة نشر Google Play معتمدة.
يتضمن الأرشيف المشروع كاملًا؛ كل ملفات P13 الأصلية الـ469 محفوظة، وعدد الملفات الأصلية المعدلة 15، ولا توجد ملفات أصلية محذوفة.
يشمل السورس Workflow مستقلًا .github/workflows/p14-standalone.yml يعيد البناء والاختبارات من الأرشيف كاملًا.
البناء: JDK17، Gradle8.11.1، Android SDK36، Build Tools36.0.0؛ أمر الهاتف gradle :app:assembleDebug.
مفتاح التوقيع يبقى خارجيًا وفق إعدادات المشروع الحالية.

## حدود الإصدار

لا تكتسب سجلات التراجع القديمة غير المثبتة ملكية الملفات الحالية؛ تُحفظ السجلات المتعارضة للمراجعة وإعادة المحاولة الآمنة.
يتطلب إثبات المحتوى قراءات إضافية أثناء عمليات النقل والتراجع؛ تعمل خارج خيط الواجهة.
مزود SAF لا يقدم حذفًا مشروطًا ذريًا، لذلك لا يمكن ضمان التزامن داخل تنفيذ المزود نفسه.
الملاحظات السابقة خارج هذا النطاق، ومنها العائلة والتنبيهات وبعض تحويلات Office، ما زالت تحتاج مراجعة منفصلة قبل اعتماد النشر التجاري.
منها أيضًا حفظ Batch كصور عند الفشل (SCAN-04)، وسباق إلغاء قص المستند الفردي (SCAN-06)، وسلوك التخزين على Android8–10 (ST-08).
تفاصيل الربط بالملاحظات السابقة في P14_VERIFICATION/P14_FINDINGS_DELTA.json، وتقييم P13 التاريخي محفوظ بوضوح كمرجع للنسخة السابقة.
'''
(root / 'PATCH_REPORT_AB98_P14_SAFE_MAINTENANCE_AR.md').write_text(report, encoding='utf-8')

def include(path):
    name = path.relative_to(root).as_posix()
    if name in baseline['files']: return True  # Preserve historical baseline files, including its existing cache file.
    return (not any(p in {'.git', '.gradle', 'build', '__pycache__'} for p in path.relative_to(root).parts)
            and not name.endswith(('.pyc', '.jks', '.p12', '.keystore', 'local.properties')))

files = sorted(p for p in root.rglob('*') if p.is_file() and include(p) and p.name != 'P14_SAFE_PATCH_MANIFEST.json')
hashes = {p.relative_to(root).as_posix(): sha(p) for p in files}
manifest = {'release': 'AB98 P14', 'version_code': 166, 'version_name': verified['version_name'],
    'baseline_commit': baseline['commit'], 'baseline_source_sha256': '3bb23ee76ed8919ebbc2312db987220f96323d26fc36e4df5158054d482d16ce',
    'ci_commit': verified['commit'], 'ci_run': verified['run_id'], 'baseline_files_retained': len(baseline['files']),
    'changed_baseline_files': changed, 'deleted_baseline_files': [], 'added_files': sorted(set(hashes) - set(baseline['files'])),
    'android_functions_passed': 79, 'scanner_ui_cases_passed': 19, 'jvm_tests_passed': 29,
    'apk_sha256': sha(phone), 'files_sha256_excluding_this_manifest': hashes,
    'physical_phone_tested': False, 'play_release_ready': False}
manifest_file = root / 'P14_SAFE_PATCH_MANIFEST.json'
manifest_file.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
files.append(manifest_file)
source = output / 'AB_FILE_PRO_V2_ALPHA98_P14_SAFE_MAINTENANCE_SAFE_SOURCE.zip'
with zipfile.ZipFile(source, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for path in files: archive.write(path, path.relative_to(root).as_posix())
with zipfile.ZipFile(source) as archive:
    assert archive.testzip() is None
    assert set(baseline['files']) <= set(archive.namelist())
    for name, digest in hashes.items(): assert hashlib.sha256(archive.read(name)).hexdigest() == digest, name
apk = output / phone.name
shutil.copy2(phone, apk)
apk_zip = output / 'AB98_P14_APK.zip'
with zipfile.ZipFile(apk_zip, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    archive.write(apk, 'AB98_P14.apk')
with zipfile.ZipFile(apk_zip) as archive:
    assert archive.testzip() is None
    assert hashlib.sha256(archive.read('AB98_P14.apk')).hexdigest() == sha(apk)
receipt = {'verified': verified, 'files': [{'name': p.name, 'path': str(p), 'bytes': p.stat().st_size, 'sha256': sha(p)} for p in [apk, source, apk_zip]]}
(output / 'RELEASE_FILES.json').write_text(json.dumps(receipt, indent=2))
(output / 'SHA256SUMS.txt').write_text(''.join(row['sha256'] + '  ' + row['name'] + '\n' for row in receipt['files']))
print(json.dumps({'packaged': [row['name'] for row in receipt['files']], 'original_files_retained': len(baseline['files']), 'checks_passed': 127}))
