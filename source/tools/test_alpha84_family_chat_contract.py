from pathlib import Path
root=Path(__file__).resolve().parents[1]
checks=[]
def c(name, ok):
    checks.append((name,bool(ok)))
    print(('PASS' if ok else 'FAIL'), name)
version=(root/'VERSION').read_text()
build=(root/'app/build.gradle.kts').read_text()
service=(root/'app/src/main/java/com/abfilepro/app/assistant/FamilyChatService.kt').read_text()
screen=(root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/FamilyChatScreen.kt').read_text()
family=(root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/FamilyRequestsOperationalScreen.kt').read_text()
cloud=(root/'app/src/main/java/com/abfilepro/app/assistant/FamilyCloudSync.kt').read_text()
c('version alpha84', '2.0.0-alpha84-family-chat' in version and 'versionCode = 145' in build)
c('chat uses v2 authenticated endpoints', '/v2/families/${session.familyCode}/chat/messages' in service and '/chat/pull' in service)
c('chat uses bearer token', 'Authorization", "Bearer $token' in service)
c('chat reuses secure family session', 'syncManager.session()' in service and 'data class FamilyCloudSession' in cloud)
c('chat identifies own messages by device id', 'message.deviceId == myDeviceId' in screen)
c('chat polls automatically', 'delay(1_500)' in screen)
c('chat sends text', 'service.send(outgoing)' in screen)
c('chat history pulls', 'service.pull()' in screen)
c('chat entry exists in family requests', 'Icons.Default.Chat' in family and 'showChat = true' in family)
c('existing family settings retained', 'showFamilySettings = true' in family)
c('existing family requests retained', 'إضافة طلب' in family)
c('message body capped', 'take(2000)' in service and 'take(2000)' in screen)
c('no duplicate legacy chat client', not (root/'app/src/main/java/com/abfilepro/app/assistant/FamilyChatClient.kt').exists())
c('material3 opt-in', '@OptIn(ExperimentalMaterial3Api::class)' in screen)
failed=[n for n,ok in checks if not ok]
print(f'Alpha84 family chat contract: {len(checks)-len(failed)}/{len(checks)} PASS')
if failed: raise SystemExit('Failed: '+', '.join(failed))
