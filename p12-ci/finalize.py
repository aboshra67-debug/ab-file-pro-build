import hashlib,json,os,shutil,subprocess,xml.etree.ElementTree as ET
from pathlib import Path
phase=Path('p12-ci/phase').read_text().strip()
if phase!='green':print('Baseline regression evidence only; no P12 APK published.');raise SystemExit(0)
p=Path('p12-evidence');p.mkdir(exist_ok=True)
run=subprocess.run(['gradle','--no-daemon','--stacktrace',':app:testDebugUnitTest'],cwd='project')
records=[]
for f in sorted(Path('project/app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')):
 for c in ET.parse(f).getroot().iter('testcase'):
  problem=c.find('failure')
  if problem is None:problem=c.find('error')
  records.append({'name':c.attrib['classname']+'.'+c.attrib['name'],'status':'FAIL' if problem is not None else 'SKIP' if c.find('skipped') is not None else 'PASS','message':problem.attrib.get('message','') if problem is not None else ''})
failed=[r for r in records if r['status']=='FAIL']
assert len(records)==29 and not any(r['status']=='SKIP' for r in records),records
assert run.returncode==1 and len(failed)==1 and failed[0]['name']=='com.abfilepro.app.core.WorkspaceShortcutRulesTest.shortcutLabelIsTrimmedAndStable',records
assert 'احمد' in failed[0]['message'] and 'إدارة ملفات AB برو' in failed[0]['message'],failed
(p/'jvm-results.json').write_text(json.dumps({'passed':28,'known_baseline_failure':failed,'results':records},ensure_ascii=False,indent=2))
apk=Path('project/app/build/outputs/apk/debug/app-debug.apk')
tools=Path(os.environ['ANDROID_SDK_ROOT'])/'build-tools/36.0.0'
badging=subprocess.check_output([str(tools/'aapt'),'dump','badging',str(apk)],text=True)
assert "name='com.abfilepro.app.p12filestrial'" in badging and "versionCode='164'" in badging and "versionName='2.0.0-alpha98-p12-file-safety'" in badging
signature=subprocess.check_output([str(tools/'apksigner'),'verify','--verbose','--print-certs',str(apk)],text=True)
subprocess.run([str(tools/'zipalign'),'-c','-P','16','-v','4',str(apk)],check=True)
(p/'apk-signature.txt').write_text(signature)
(p/'apk-badging.txt').write_text(badging)
out=Path('p12-release');out.mkdir(exist_ok=True)
final=out/'AB98_P12_FILE_SAFETY_TEST.apk';shutil.copy2(apk,final)
sha=hashlib.sha256(final.read_bytes()).hexdigest()
(out/'APK_SHA256.txt').write_text(sha+'  '+final.name+'\n')
print('P12_APK_VERIFIED',sha,final.stat().st_size,flush=True)
