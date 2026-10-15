from pathlib import Path

root = Path(__file__).resolve().parents[1]
checks = []
def ck(name, ok): checks.append((name, bool(ok)))

version = (root/'VERSION').read_text(encoding='utf-8')
build = (root/'app/build.gradle.kts').read_text(encoding='utf-8')
screen = (root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/DocumentsRenewalsScreen.kt').read_text(encoding='utf-8')
codec = (root/'app/src/main/java/com/abfilepro/app/assistant/DocumentRenewalCodec.kt').read_text(encoding='utf-8')
cloud = (root/'app/src/main/java/com/abfilepro/app/assistant/FamilyCloudSync.kt').read_text(encoding='utf-8')

ck('alpha87 version', '2.0.0-alpha87-documents-ui-types' in version and 'versionCode = 148' in build)
ck('package preserved', 'applicationId = "com.abfilepro.app"' in build)
ck('rental contract kind', 'RENTAL_CONTRACT("rental_contract", "عقد إيجار")' in codec)
ck('visa kind', 'VISA("visa", "تأشيرة / فيزا")' in codec)
ck('car license kind', 'CAR_LICENSE("car_license", "رخصة السيارة")' in codec)
ck('metadata backward compatible', 'issuedDate: String = ""' in codec and 'country: String = ""' in codec and 'customType: String = ""' in codec)
ck('clean dropdown UI', 'ExposedDropdownMenuBox' in screen and 'DocumentKind.entries.forEach' in screen)
ck('rental dates', 'تاريخ بداية العقد' in screen and 'تاريخ انتهاء العقد' in screen)
ck('visa fields', 'الدولة' in screen and 'تاريخ الإصدار' in screen)
ck('custom other type', 'اسم نوع المستند' in screen)
ck('reminder validation', 'lead in 0..365' in screen)
ck('documents stay local', 'AssistantModule.DOCUMENTS' not in cloud)
ck('family sync preserved', 'AssistantModule.FAMILY_REQUESTS' in cloud and 'AssistantModule.HEALTH' in cloud)

failed=[n for n,ok in checks if not ok]
for n,ok in checks: print(('PASS' if ok else 'FAIL'), n)
print(f'Alpha87 documents UI/types contract: {len(checks)-len(failed)}/{len(checks)} PASS')
if failed: raise SystemExit('Failed: '+', '.join(failed))
