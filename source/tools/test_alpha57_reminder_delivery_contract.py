from pathlib import Path

root = Path(__file__).resolve().parents[1]
read = lambda rel: (root / rel).read_text(encoding='utf-8')

manifest = read('app/src/main/AndroidManifest.xml')
scheduler = read('app/src/main/java/com/abfilepro/app/assistant/TaskAlarmScheduler.kt')
receiver = read('app/src/main/java/com/abfilepro/app/assistant/TaskAlarmReceiver.kt')
tasks = read('app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantTasksScreen.kt')
repo = read('app/src/main/java/com/abfilepro/app/assistant/AssistantRepository.kt')
personal = read('app/src/main/java/com/abfilepro/app/ui/screens/assistant/PersonalAssistantScreen.kt')
module_screen = read('app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantModuleScreens.kt')
gradle = read('app/build.gradle.kts')
version = read('VERSION')

checks = {
    'version code 118': 'versionCode = 118' in gradle,
    'version alpha57': '2.0.0-alpha57-reminder-delivery-fix' in gradle and '2.0.0-alpha57-reminder-delivery-fix' in version,
    'notification permission': 'android.permission.POST_NOTIFICATIONS' in manifest,
    'exact alarm permission': 'android.permission.SCHEDULE_EXACT_ALARM' in manifest,
    'task receiver registered': '.assistant.TaskAlarmReceiver' in manifest,
    'task rescheduler registered': '.assistant.TaskRescheduleReceiver' in manifest,
    'exact permission state receiver': 'android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED' in manifest,
    'exact access checked': 'canScheduleExactAlarms()' in scheduler,
    'exact idle alarm only': 'setExactAndAllowWhileIdle' in scheduler and 'setAndAllowWhileIdle' not in scheduler,
    'notification permission runtime': 'RequestPermission()' in tasks and 'POST_NOTIFICATIONS' in tasks,
    'exact alarm settings runtime': 'ACTION_REQUEST_SCHEDULE_EXACT_ALARM' in tasks,
    'task add carries reminder': 'onAddTask' in tasks and 'enabled = reminder' in personal,
    'repository stores add enabled': 'enabled: Boolean = true' in repo and 'enabled = enabled' in repo,
    'tabs remain': all(x in tasks for x in ['TaskTab.TODAY', 'TaskTab.UPCOMING', 'TaskTab.COMPLETED']),
    'task dedicated route': 'AssistantTasksScreen(entries, defaultCategory, onBack, onAddTask, onUpdate, onDelete)' in module_screen,
    'receiver posts notification': 'NotificationManagerCompat.from(context).notify' in receiver,
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' - ' + name)
if failed:
    raise SystemExit('Contract failed: ' + ', '.join(failed))
print(f'Alpha57 reminder delivery contract PASS={len(checks)} FAIL=0')
