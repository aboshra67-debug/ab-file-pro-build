from pathlib import Path
root=Path(__file__).resolve().parents[1]
module=(root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantModuleScreens.kt').read_text()
ref=(root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantReferenceVisuals.kt').read_text()
build=(root/'app/build.gradle.kts').read_text()
version=(root/'VERSION').read_text()
checks={
 'identity':'2.0.0-alpha72-operational-reference-calculator' in version and 'versionCode = 133' in build,
 'direct_route':'if (module == AssistantModule.CALCULATOR)' in module and 'CalculatorModuleScreen(onBack)' in module,
 'no_second_calc':'OperationalReferenceCalculator(' in module and 'onOpenCurrency = { mode = "currency" }' in module,
 'reference_visual':'painterResource(R.drawable.pa_ref_calculator)' in ref,
 'operational_keys':'RefHit(27f, 508f' in ref and 'RefHit(478f, 731f, 140f, 214f, ::commitResult)' in ref,
 'live_output':'AssistantDomain.evaluateExpression(expression)' in ref and 'calcDisplay(result)' in ref,
 'history':'CalculatorHistoryRow' in ref and 'history = emptyList()' in ref,
 'copy':'ClipboardManager' in ref and 'تم نسخ النتيجة' in ref,
 'currency_preserved':'CurrencyConverterPanel(' in module and 'CurrencyRateService.load' in module,
}
failed=[k for k,v in checks.items() if not v]
for k,v in checks.items(): print(('[PASS] ' if v else '[FAIL] ')+k)
if failed: raise SystemExit('Alpha72 contract failed: '+', '.join(failed))
print('Alpha72 operational reference calculator contract: PASS')
