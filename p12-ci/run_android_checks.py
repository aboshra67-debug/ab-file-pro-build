import json,subprocess,xml.etree.ElementTree as ET
from pathlib import Path

phase=Path('p12-ci/phase').read_text().strip()
assert phase in ('red','red-followup','green')
p=Path('p12-evidence');p.mkdir(exist_ok=True)
run=subprocess.run(['gradle','--no-daemon','--stacktrace',':app:connectedDebugAndroidTest'],cwd='project')
records=[]
for f in sorted(Path('project/app/build/outputs/androidTest-results').rglob('TEST-*.xml')):
    for case in ET.parse(f).getroot().iter('testcase'):
        failure=case.find('failure');error=case.find('error');skipped=case.find('skipped')
        status='FAIL' if failure is not None or error is not None else 'SKIP' if skipped is not None else 'PASS'
        records.append({'name':case.attrib['name'],'classname':case.attrib.get('classname'),'status':status,'details':(failure.text or '') if failure is not None else (error.text or '') if error is not None else ''})
assert len(records)==(16 if phase=='red' else 22),(run.returncode,len(records),records)
assert not any(x['status']=='SKIP' for x in records),records
expected_red={'copyIntoSelfRejectedWithoutChangingSource','copyIntoDescendantRejectedWithoutWriting','copyReadFailureCleansOnlyAttemptAndPreservesExistingDestination','copyCancellationCleansAttemptAndKeepsSource','unreadableDirectoryCannotBePublishedAsEmptyCopy','fallbackMoveDeleteFailureKeepsCompleteDestination','moveCancellationBeforeSourceCleanupPreservesSource','undoChangedSameLengthSameTimeFilePreservesNewData','undoReplacementAtSamePathPreservesReplacement','undoFolderWithNewUserFilePreservesWholeFolder','partialUndoKeepsChangedOutputAndItsHistory','legacyHistoryWithoutOwnershipIsKeptWithoutDeletingFile','partialFileMoveUndoRetainsConflictForRetry'}
failed={x['name'] for x in records if x['status']=='FAIL'}
summary={'phase':phase,'tests':len(records),'passed':len(records)-len(failed),'failed':len(failed),'gradle_exit':run.returncode,'results':records}
(p/(phase+'-android-results.json')).write_text(json.dumps(summary,ensure_ascii=False,indent=2))
if phase in ('red','red-followup'):
    expected = expected_red if phase=='red' else {'fallbackMoveKeepsFileAddedAfterCopy','fallbackMoveKeepsSameLengthSameTimeEditAfterCopy'}
    assert run.returncode!=0 and failed==expected,summary
    assert all('AssertionError' in x['details'] for x in records if x['status']=='FAIL'),summary
else:
    assert run.returncode==0 and not failed,summary
    package='com.abfilepro.app.p12filestrial'
    # The connected-test runner cleans installed packages after instrumentation.
    # Reinstall the same built APK for the independent launcher smoke check.
    installed=subprocess.check_output(['adb','shell','pm','path',package],text=True)
    (p/'startup-install-state-before.txt').write_text(installed)
    subprocess.run(['adb','install','-r','project/app/build/outputs/apk/debug/app-debug.apk'],check=True)
    launch=subprocess.check_output(['adb','shell','am','start','-W','-n',package+'/com.abfilepro.app.MainActivity'],text=True)
    (p/'startup-launch.txt').write_text(launch)
    assert 'Status: ok' in launch,launch
    subprocess.run(['adb','shell','uiautomator','dump','/sdcard/p12-startup.xml'],check=True)
    subprocess.run(['adb','pull','/sdcard/p12-startup.xml',str(p/'startup.xml')],check=True)
    ui=ET.parse(p/'startup.xml')
    assert any(node.attrib.get('package')==package for node in ui.iter('node')),'Target app UI did not appear'
    with (p/'startup.png').open('wb') as output:
        subprocess.run(['adb','exec-out','screencap','-p'],stdout=output,check=True)
    crash=subprocess.check_output(['adb','logcat','-d','-b','crash'],text=True)
    (p/'startup-crash-buffer.txt').write_text(crash)
    assert ('Process: '+package) not in crash,crash
    (p/'startup-summary.json').write_text(json.dumps({'launch_status':'ok','target_ui_visible':True,'target_crash':False,'package':package},indent=2))
    print('P12_STARTUP_VERIFIED',package,flush=True)
print('P12_'+phase.upper().replace('-','_')+'_VERIFIED',len(records),'tests;',len(failed),'expected failures' if phase!='green' else 'failures',flush=True)
