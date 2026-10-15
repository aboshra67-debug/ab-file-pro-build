from pathlib import Path

root = Path(__file__).resolve().parents[1]
checks = []
def ck(name, ok):
    checks.append((name, bool(ok)))

version = (root/'VERSION').read_text(encoding='utf-8')
build = (root/'app/build.gradle.kts').read_text(encoding='utf-8')
dispatch = (root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantModuleScreens.kt').read_text(encoding='utf-8')
screen = (root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/HealthMedicationScreen.kt').read_text(encoding='utf-8')
codec = (root/'app/src/main/java/com/abfilepro/app/assistant/HealthEntryCodec.kt').read_text(encoding='utf-8')
scheduler = (root/'app/src/main/java/com/abfilepro/app/assistant/HealthAlarmScheduler.kt').read_text(encoding='utf-8')
receiver = (root/'app/src/main/java/com/abfilepro/app/assistant/HealthAlarmReceiver.kt').read_text(encoding='utf-8')
repo = (root/'app/src/main/java/com/abfilepro/app/assistant/AssistantRepository.kt').read_text(encoding='utf-8')
cloud = (root/'app/src/main/java/com/abfilepro/app/assistant/FamilyCloudSync.kt').read_text(encoding='utf-8')
manifest = (root/'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')

ck('version alpha85', '2.0.0-alpha85-health-medication' in version and 'versionCode = 146' in build)
ck('native health dispatch', 'HealthMedicationScreen(' in dispatch and 'AssistantModule.HEALTH' in dispatch)
ck('self partner tabs', 'HealthOwner.SELF' in screen and 'HealthOwner.PARTNER' in screen)
ck('next dose card', 'جرعة الدواء القادمة' in screen)
ck('today medications', 'جدول اليوم' in screen and 'تم تناوله اليوم' in screen)
ck('health appointments', 'مواعيد صحية' in screen and 'إضافة موعد صحي' in screen)
ck('add medication', 'إضافة دواء' in screen and 'موعد الجرعة' in screen)
ck('medical disclaimer', 'ولا يقدم تشخيصًا أو علاجًا طبيًا' in screen)
ck('daily medication recurrence', 'nextMedicationTrigger' in codec and 'lastTakenDate' in codec)
ck('exact local reminders', 'setExactAndAllowWhileIdle' in scheduler and 'HealthAlarmReceiver' in scheduler)
ck('health notification receiver', 'الأدوية والمواعيد الصحية' in receiver and '.assistant.HealthAlarmReceiver' in manifest)
ck('health cloud change tracking', 'AssistantModule.HEALTH' in repo and 'markChanged' in repo)
ck('health cloud payload', 'setOf(AssistantModule.FAMILY_REQUESTS, AssistantModule.HEALTH)' in cloud and '.put("module", entry.module.storageId)' in cloud)
ck('health remote decode', 'decodedModule' in cloud and 'AssistantModule.HEALTH' in cloud)
ck('no app key regression', 'FAMILY_SYNC_APP_KEY' not in build)

failed=[n for n,ok in checks if not ok]
for n,ok in checks: print(('PASS' if ok else 'FAIL'), n)
print(f'Alpha85 health medication contract: {len(checks)-len(failed)}/{len(checks)} PASS')
if failed: raise SystemExit('Failed: '+', '.join(failed))
