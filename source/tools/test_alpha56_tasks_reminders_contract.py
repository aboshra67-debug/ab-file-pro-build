from pathlib import Path
root=Path(__file__).resolve().parents[1]
def has(p,*needles):
    s=(root/p).read_text(encoding='utf-8')
    for n in needles: assert n in s, f'{n} missing from {p}'
has('app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantModuleScreens.kt','if (module == AssistantModule.TASKS)','AssistantTasksScreen')
has('app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantTasksScreen.kt','TaskTab.TODAY','TaskTab.UPCOMING','TaskTab.COMPLETED','تنبيه في موعد المهمة','onUpdate','onDelete')
has('app/src/main/java/com/abfilepro/app/assistant/TaskAlarmScheduler.kt','setExactAndAllowWhileIdle','rescheduleAll','sync')
has('app/src/main/java/com/abfilepro/app/assistant/TaskAlarmReceiver.kt','NotificationCompat.Builder','POST_NOTIFICATIONS')
has('app/src/main/AndroidManifest.xml','.assistant.TaskAlarmReceiver','.assistant.TaskRescheduleReceiver','BOOT_COMPLETED')
has('app/build.gradle.kts','versionCode = 117','2.0.0-alpha56-tasks-reminders')
print('ALPHA56_TASKS_REMINDERS: PASS')
