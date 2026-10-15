import hashlib,json,re,subprocess,zipfile,xml.etree.ElementTree as ET
from pathlib import Path

phase=Path('p13-ci/phase').read_text().strip()
assert phase in ('red','green')
p=Path('p13-evidence');p.mkdir(exist_ok=True)
run=subprocess.run(['gradle','--no-daemon','--stacktrace','-PAB_FILE_PRO_NATIVE_TEST=true',':app:connectedDebugAndroidTest'],cwd='project')
records=[]
for f in sorted(Path('project/app/build/outputs/androidTest-results').rglob('TEST-*.xml')):
    for case in ET.parse(f).getroot().iter('testcase'):
        failure=case.find('failure');error=case.find('error');skipped=case.find('skipped')
        status='FAIL' if failure is not None or error is not None else 'SKIP' if skipped is not None else 'PASS'
        records.append({'name':case.attrib['name'],'classname':case.attrib.get('classname'),'status':status,'details':(failure.text or '') if failure is not None else (error.text or '') if error is not None else ''})
assert len(records)==(33 if phase=='red' else 46),(run.returncode,len(records),records)
assert not any(x['status']=='SKIP' for x in records),records
expected_red={'imageChosenFromShareFolderNeverReplacesItsSource','pdfChosenFromShareFolderNeverReplacesItsSource','existingImageWithSameNameIsPreserved','existingPdfWithSameNameIsPreserved','parallelImagesWithSameNameHaveIndependentOutputs','malformedPdfLeavesNoAttemptFiles','largerCompressionCandidateIsNotReportedAsCompressed'}
failed={x['name'] for x in records if x['status']=='FAIL'}
summary={'phase':phase,'tests':len(records),'passed':len(records)-len(failed),'failed':len(failed),'gradle_exit':run.returncode,'results':records}
(p/(phase+'-android-results.json')).write_text(json.dumps(summary,ensure_ascii=False,indent=2))
if phase=='red':
    expected = expected_red
    assert run.returncode!=0 and failed==expected,summary
    assert all('AssertionError' in x['details'] for x in records if x['status']=='FAIL'),summary
else:
    assert run.returncode==0 and not failed,summary
    package='com.abfilepro.app.p13sharetrial'
    apk=Path('project/app/build/outputs/apk/debug/app-debug.apk')
    def payload(file):
        with zipfile.ZipFile(file) as z:
            assert z.testzip() is None
            return {n:hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if not n.endswith('/') and not n.startswith(('META-INF/','lib/x86_64/'))}
    tested=payload(apk)
    native_test_sha=hashlib.sha256(apk.read_bytes()).hexdigest()
    with zipfile.ZipFile(apk) as z:
        assert any(n.startswith('lib/x86_64/') for n in z.namelist())
        assert any(n.startswith('lib/arm64-v8a/') for n in z.namelist())
    # Rebuild the default ARM64 phone APK, preserving the project's size policy.
    # Prove every shared packaged entry (including ARM native libraries) is identical.
    subprocess.run(['gradle','--no-daemon','--stacktrace',':app:assembleDebug'],cwd='project',check=True)
    delivered=payload(apk)
    assert tested==delivered,{'added':sorted(set(delivered)-set(tested)),'missing':sorted(set(tested)-set(delivered)),'changed':[n for n in tested.keys()&delivered.keys() if tested[n]!=delivered[n]]}
    with zipfile.ZipFile(apk) as z:
        assert {n.split('/')[1] for n in z.namelist() if n.startswith('lib/')}=={'arm64-v8a'}
    (p/'native-test-to-phone-payload.json').write_text(json.dumps({'native_test_apk_sha256':native_test_sha,'phone_apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'native_test_abis':['arm64-v8a','x86_64'],'phone_abis':['arm64-v8a'],'shared_entries_verified':len(tested),'all_shared_entries_identical':True,'shared_payload_sha256':delivered},indent=2))
    # The connected-test runner cleans installed packages after instrumentation.
    # Reinstall the same built APK for the independent launcher smoke check.
    installed=subprocess.run(['adb','shell','pm','path',package],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,check=False).stdout
    (p/'startup-install-state-before.txt').write_text(installed)
    subprocess.run(['adb','install','-r','project/app/build/outputs/apk/debug/app-debug.apk'],check=True)
    launch=subprocess.check_output(['adb','shell','am','start','-W','-n',package+'/com.abfilepro.app.MainActivity'],text=True)
    (p/'startup-launch.txt').write_text(launch)
    assert 'Status: ok' in launch,launch
    subprocess.run(['adb','shell','uiautomator','dump','/sdcard/p13-startup.xml'],check=True)
    subprocess.run(['adb','pull','/sdcard/p13-startup.xml',str(p/'startup.xml')],check=True)
    ui=ET.parse(p/'startup.xml')
    shortcut_prompt_dismissed=False
    if not any(node.attrib.get('package')==package for node in ui.iter('node')):
        cancel=next((node for node in ui.iter('node') if node.attrib.get('package')=='com.google.android.apps.nexuslauncher' and node.attrib.get('text')=='Cancel'),None)
        if cancel is not None:
            (p/'startup-shortcut-dialog.xml').write_bytes((p/'startup.xml').read_bytes())
            bounds=list(map(int,re.findall(r'\d+',cancel.attrib['bounds'])))
            assert len(bounds)==4,bounds
            subprocess.run(['adb','shell','input','tap',str((bounds[0]+bounds[2])//2),str((bounds[1]+bounds[3])//2)],check=True)
            subprocess.run(['adb','shell','am','start','-W','-n',package+'/com.abfilepro.app.MainActivity'],check=True)
            subprocess.run(['adb','shell','uiautomator','dump','/sdcard/p13-startup.xml'],check=True)
            subprocess.run(['adb','pull','/sdcard/p13-startup.xml',str(p/'startup.xml')],check=True)
            ui=ET.parse(p/'startup.xml')
            shortcut_prompt_dismissed=True
    with (p/'startup.png').open('wb') as output:
        subprocess.run(['adb','exec-out','screencap','-p'],stdout=output,check=True)
    crash=subprocess.check_output(['adb','logcat','-d','-b','crash'],text=True)
    (p/'startup-crash-buffer.txt').write_text(crash)
    assert ('Process: '+package) not in crash,crash
    assert any(node.attrib.get('package')==package for node in ui.iter('node')),'Target app UI did not appear'
    (p/'startup-summary.json').write_text(json.dumps({'launch_status':'ok','target_ui_visible':True,'target_crash':False,'package':package,'existing_launcher_shortcut_prompt_dismissed':shortcut_prompt_dismissed},indent=2))
    print('P13_STARTUP_VERIFIED',package,flush=True)
print('P13_'+phase.upper().replace('-','_')+'_VERIFIED',len(records),'tests;',len(failed),'expected failures' if phase!='green' else 'failures',flush=True)
