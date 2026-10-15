from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
an = (ROOT/'app/src/main/java/com/abfilepro/app/core/DocumentFrameAnalyzer.kt').read_text(encoding='utf-8')
cam = (ROOT/'app/src/main/java/com/abfilepro/app/ui/screens/ScannerProCamera.kt').read_text(encoding='utf-8')
checks = {
    'outer-biased boundary selection': 'preferOuter' in an and 'smoothedHits' in an,
    'document minimum area gate': 'minAreaForMode' in an,
    'hide unstable overlay': 'displayQuad' in an and 'stableFrames >= 2' in an,
    'manual crop requires captureReady': 'metrics?.captureReady == true' in cam and 'detectedQuad' in cam,
}
failed=[name for name,ok in checks.items() if not ok]
if failed:
    print('FAIL:', ', '.join(failed))
    raise SystemExit(1)
print('PASS:', ', '.join(checks))
