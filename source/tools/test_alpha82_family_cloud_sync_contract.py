from pathlib import Path
root=Path(__file__).resolve().parents[1]
build=(root/'app/build.gradle.kts').read_text()
version=(root/'VERSION').read_text().strip()
cloud=(root/'app/src/main/java/com/abfilepro/app/assistant/FamilyCloudSync.kt').read_text()
repo=(root/'app/src/main/java/com/abfilepro/app/assistant/AssistantRepository.kt').read_text()
link=(root/'app/src/main/java/com/abfilepro/app/assistant/FamilyLinkStore.kt').read_text()
family=(root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/FamilyRequestsOperationalScreen.kt').read_text()
personal=(root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/PersonalAssistantScreen.kt').read_text()
server=(root/'family-sync-server/main.py').read_text()
checks={
 'version': version=='2.0.0-alpha82-family-cloud-sync',
 'version_code': 'versionCode = 143' in build,
 'real_https_url': 'https://family-sync-api-production.up.railway.app' in build and '.invalid' not in build,
 'build_config_enabled': 'buildConfig = true' in build,
 'cloud_client': 'class FamilyCloudSyncManager' in cloud and 'HttpURLConnection' in cloud,
 'family_code_endpoint': '/v1/families/$familyCode/sync' in cloud,
 'app_key_header': 'X-App-Key' in cloud,
 'offline_timestamps': 'object FamilySyncStateStore' in cloud and 'markDeleted' in cloud,
 'repo_marks_family_changes': 'FamilySyncStateStore.markChanged' in repo and 'FamilySyncStateStore.markDeleted' in repo,
 'periodic_sync': 'familyCloudSync.sync()' in personal and 'delay(3_000)' in personal,
 'editable_join_code': 'كود العائلة - 6 أرقام' in family and 'inviteCode = inviteCode' in family,
 'secure_code_generation': 'SecureRandom' in link,
 'linked_shopping_preserved': 'addFamilyRequestToShopping' in family and 'setFamilyRequestShoppingCompletion' in family,
 'backend_fastapi': 'FastAPI' in server and '/v1/families/{family_code}/sync' in server,
 'backend_postgres': 'psycopg' in server and 'family_sync_items' in server,
 'backend_tombstone': 'deleted BOOLEAN' in server and 'ON CONFLICT' in server,
}
failed=[k for k,v in checks.items() if not v]
if failed:
    raise SystemExit('FAIL '+','.join(failed))
print('Alpha82 family cloud sync contract: PASS',len(checks),'checks')
