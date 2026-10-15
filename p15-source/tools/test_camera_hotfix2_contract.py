from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
an=(ROOT/'app/src/main/java/com/abfilepro/app/core/DocumentFrameAnalyzer.kt').read_text()
ui=(ROOT/'app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt').read_text()
assert 'missedCandidateFrames' in an, 'temporal hold counter missing'
assert 'displayQuadState' in an, 'stable display quad state missing'
assert 'smoothQuad' in an, 'quad smoothing missing'
assert 'missedCandidateFrames <= 4' in an, 'short detection dropouts are not held'
assert 'renderSourcePreview(page, 360)' in ui, 'fast source preview missing'
assert 'renderPreview(page)' in ui, 'processed preview missing'
print('camera hotfix2 contract: PASS')
