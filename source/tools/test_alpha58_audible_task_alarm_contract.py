from pathlib import Path
root=Path(__file__).resolve().parents[1]
receiver=(root/'app/src/main/java/com/abfilepro/app/assistant/TaskAlarmReceiver.kt').read_text()
manifest=(root/'app/src/main/AndroidManifest.xml').read_text()
build=(root/'app/build.gradle.kts').read_text()
checks={
'new_channel_id': 'assistant_tasks_alarm_v2' in receiver,
'alarm_sound': 'DEFAULT_ALARM_ALERT_URI' in receiver,
'alarm_usage': 'USAGE_ALARM' in receiver,
'vibration_channel': 'enableVibration(true)' in receiver and 'vibrationPattern' in receiver,
'vibration_notification': '.setVibrate(' in receiver,
'category_alarm': 'CATEGORY_ALARM' in receiver,
'priority_max': 'PRIORITY_MAX' in receiver,
'public_visibility': 'VISIBILITY_PUBLIC' in receiver,
'vibrate_permission': 'android.permission.VIBRATE' in manifest,
'exact_alarm_permission': 'android.permission.SCHEDULE_EXACT_ALARM' in manifest,
'post_notifications_permission': 'android.permission.POST_NOTIFICATIONS' in manifest,
'version_code': 'versionCode = 119' in build,
'version_name': '2.0.0-alpha58-audible-task-alarm' in build,
}
for k,v in checks.items(): print(('PASS' if v else 'FAIL'), k)
if not all(checks.values()): raise SystemExit(1)
print(f'PASS {sum(checks.values())}/{len(checks)}')
