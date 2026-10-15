import json
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

phase = Path('p14-ci/phase').read_text().strip()
assert phase in ('red', 'green')
output = Path('p14-evidence')
output.mkdir(exist_ok=True)
run = subprocess.run(['gradle', '--no-daemon', '--stacktrace', '-PAB_FILE_PRO_NATIVE_TEST=true', ':app:connectedDebugAndroidTest'], cwd='project')
with (output / (phase + '-android-logcat.txt')).open('w') as log:
    subprocess.run(['adb', 'logcat', '-d', '-v', 'threadtime'], stdout=log, stderr=subprocess.STDOUT)
records = []
for file in sorted(Path('project/app/build/outputs/androidTest-results').rglob('TEST-*.xml')):
    for case in ET.parse(file).getroot().iter('testcase'):
        problem = case.find('failure')
        if problem is None:
            problem = case.find('error')
        records.append({
            'name': case.attrib['name'], 'class': case.attrib.get('classname', ''),
            'status': 'FAIL' if problem is not None else 'SKIP' if case.find('skipped') is not None else 'PASS',
            'details': ((problem.text or '') + '\n' + problem.attrib.get('message', '')) if problem is not None else '',
        })
summary = {'phase': phase, 'gradle_exit': run.returncode, 'tests': len(records),
           'passed': sum(r['status'] == 'PASS' for r in records),
           'failed': sum(r['status'] == 'FAIL' for r in records), 'results': records}
(output / (phase + '-android-results.json')).write_text(json.dumps(summary, ensure_ascii=False, indent=2))
assert len(records) == 79, summary
assert not any(r['status'] == 'SKIP' for r in records), summary
if phase == 'red':
    expected_failures = {
        'arabicDecimalAmountAndTaxStayComplete', 'arabicThousandsAndDecimalStayComplete',
        'persianDigitsAndArabicSeparatorsStayComplete', 'businessNameSurvivesMissingEmail',
        'businessNameSurvivesMissingWebsite', 'businessNameSurvivesMissingPhone',
        'arabicNameSurvivesAllContactFieldsMissing', 'fullBusinessContactFieldsStillParse', 'singlePdfPageRendersAsLongImage',
        'multiplePdfPagesRenderInOrderAsLongImage', 'unreadableImageFailsEntirePdfWithoutPublishingMissingPages',
        'failedZipEnumerationCannotSucceedWithEmptyDirectory', 'corruptZipRemovesPartialFileAndImplicitDirectories',
        'renameUndoNeverMovesAReplacementFile', 'moveUndoNeverMovesAReplacementFile',
        'legacyFallbackDeleteFailureKeepsCompletedCopy', 'legacyFallbackNeverDeletesSourceModifiedAfterCopy',
        'nullSafCursorIsAnErrorRatherThanEmptyFolder', 'failedSafListingCannotMoveAndDeleteUncopiedFolder',
        'safMoveKeepsFileModifiedAfterCopy', 'safMoveKeepsChildAddedAfterCopy',
        'safMoveRejectsIncompleteDestinationBeforeDeletingOriginal',
        'concaveCropIsRejectedBeforeChangingCapturedImage', 'crossingCropIsRejectedBeforeChangingCapturedImage',
        'fullFrameCropDoesNotReencodeOrDeleteSource',
        'zipCancellationKeepsFileEditedAfterExtraction', 'legacyFallbackFailureKeepsReplacementAtDestination',
        'nonemptyUnreadableScannerImportCannotBecomeReplacement',
    }
    failed = {r['name'] for r in records if r['status'] == 'FAIL'}
    assert run.returncode != 0 and failed <= expected_failures, summary
    required = {'arabicDecimalAmountAndTaxStayComplete', 'businessNameSurvivesMissingEmail',
                'singlePdfPageRendersAsLongImage', 'failedZipEnumerationCannotSucceedWithEmptyDirectory',
                'renameUndoNeverMovesAReplacementFile', 'unreadableImageFailsEntirePdfWithoutPublishingMissingPages'}
    assert required <= failed, summary
    print('P14_BASELINE_DEFECTS_REPRODUCED', sorted(failed), flush=True)
else:
    assert run.returncode == 0 and summary['failed'] == 0, summary
    print('P14_ANDROID_FUNCTIONS_VERIFIED', len(records), flush=True)
