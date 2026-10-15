from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
rules = (ROOT/'app/src/main/java/com/abfilepro/app/core/ScanInteractionRules.kt').read_text(encoding='utf-8')
scan = (ROOT/'app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt').read_text(encoding='utf-8')
assert 'ScanFlowStage.REVIEW -> ScanBackTarget.HUB' in rules, 'review back must go to Smart Scan hub'
assert 'ScanBackTarget.HUB -> {' in scan, 'review UI must handle HUB target internally'
print('ALPHA13_BACK_FIX_CONTRACT: PASS')
