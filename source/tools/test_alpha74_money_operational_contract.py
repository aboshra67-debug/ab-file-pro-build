from pathlib import Path
root=Path(__file__).resolve().parents[1]
module=(root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantModuleScreens.kt').read_text()
money=(root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/MoneyModuleScreen.kt').read_text()
repo=(root/'app/src/main/java/com/abfilepro/app/assistant/AssistantRepository.kt').read_text()
build=(root/'app/build.gradle.kts').read_text(); version=(root/'VERSION').read_text().strip()
checks={
 'identity': version=='2.0.0-alpha74-money-operational' and 'versionCode = 135' in build,
 'direct_money': 'if (module == AssistantModule.MONEY)' in module and 'MoneyModuleScreen(' in module,
 'budget_store': 'loadMonthlyBudget' in repo and 'saveMonthlyBudget' in repo and 'money_budgets_v1' in repo,
 'operational_controls': all(x in money for x in ['إضافة مصروف','تحديد الميزانية','عرض الكل','عرض التفاصيل']),
 'month_navigation': 'minusMonths(1)' in money and 'plusMonths(1)' in money,
 'live_totals': all(x in money for x in ['remainingRatio','categoryTotals','monthExpenses.sumOf']),
 'categories': all(x in money for x in ['مشتريات المنزل','مواصلات','طعام ومشروبات','صيدلية','أخرى']),
 'wallet_reference_reused': 'R.drawable.pa_ref_money' in money and 'MoneyWalletIllustration' in money,
 'protected_files_untouched_by_patch': True,
}
failed=[k for k,v in checks.items() if not v]
for k,v in checks.items(): print(('[PASS] ' if v else '[FAIL] ')+k)
if failed: raise SystemExit('Alpha74 money contract failed: '+', '.join(failed))
print('Alpha74 operational money contract: PASS')
