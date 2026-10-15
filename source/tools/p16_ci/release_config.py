"""Single source of the values P16 verification pins (version, packages, expected counts)."""
CI_DIR = 'p16-ci'
EVIDENCE = 'p16-evidence'
RELEASE = 'p16-release'
VERSION_CODE = 168
VERSION_NAME = '2.0.0-alpha98-p16-play-channel'
TEST_PACKAGE = 'com.abfilepro.app.p16mainttrial'      # playDebug (instrumented + Scanner UI)
QA_PACKAGE = 'com.abfilepro.app.p16qa'                 # playQa = release R8 code, debug-signed
TEST_LABEL = 'AB File Pro P16'
QA_LABEL = 'AB File Pro P16 QA'
DEBUG_APK = 'project/app/build/outputs/apk/play/debug/app-play-debug.apk'
QA_APK = 'project/app/build/outputs/apk/play/qa/app-play-qa.apk'
DIRECT_APK = 'project/app/build/outputs/apk/direct/debug/app-direct-debug.apk'
AAB = 'project/app/build/outputs/bundle/playRelease/app-play-release.aab'
MAPPING = 'project/app/build/outputs/mapping/playRelease/mapping.txt'
ANDROID_TESTS = 86   # 79 P14 + 5 P15 SCAN-04 + 2 P16 Play channel
SCANNER_CASES = 19
JVM_TESTS = 29
P15_REQUIRED = {
    'successfulBatchPublishesEveryPageInOrder', 'secondPageFailureRemovesOnlyThisAttemptsImages',
    'retryAfterFailureDoesNotDuplicatePages', 'cancellationDuringSaveRemovesPublishedImages',
    'imageChangedAfterPublishIsKeptOnRollback',
}
P16_REQUIRED = {'playChannelDisablesSelfUpdate', 'playBuildDoesNotRequestInstallPackages'}
