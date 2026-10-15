from pathlib import Path
root=Path(__file__).resolve().parents[1]
shopping=(root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/ShoppingListOperationalScreen.kt').read_text()
family=(root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/FamilyRequestsOperationalScreen.kt').read_text()
link=(root/'app/src/main/java/com/abfilepro/app/assistant/FamilyLinkStore.kt').read_text()
routes=(root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantModuleScreens.kt').read_text()
build=(root/'app/build.gradle.kts').read_text()
version=(root/'VERSION').read_text().strip()
checks={
 'version': version=='2.0.0-alpha81-family-shopping-linked',
 'code': 'versionCode = 142' in build,
 'shopping_family_id': 'familyRequestId' in shopping,
 'family_to_shopping': 'addFamilyRequestToShopping' in family,
 'shopping_to_family': 'onUpdateFamilyRequest' in shopping,
 'shared_status': 'FamilyLinkStore.withStatus' in shopping and 'FamilyLinkStore.withStatus' in family,
 'catalog_request': 'catalogFor(category)' in family,
 'family_profile': 'كود العائلة' in family and 'FamilyProfile' in link,
 'route_shopping_entries': 'onUpdateFamilyRequest = onUpdate' in routes,
 'accept_text': 'سأشتريها' in family,
}
failed=[k for k,v in checks.items() if not v]
if failed:
    raise SystemExit('FAIL '+','.join(failed))
print('Alpha81 family-shopping link contract: PASS', len(checks),'checks')
