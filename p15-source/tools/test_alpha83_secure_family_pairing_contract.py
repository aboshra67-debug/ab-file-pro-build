from pathlib import Path

root = Path(__file__).resolve().parents[1]
build = (root/'app/build.gradle.kts').read_text(encoding='utf-8')
sync = (root/'app/src/main/java/com/abfilepro/app/assistant/FamilyCloudSync.kt').read_text(encoding='utf-8')
store = (root/'app/src/main/java/com/abfilepro/app/assistant/FamilyLinkStore.kt').read_text(encoding='utf-8')
screen = (root/'app/src/main/java/com/abfilepro/app/ui/screens/assistant/FamilyRequestsOperationalScreen.kt').read_text(encoding='utf-8')
version = (root/'VERSION').read_text(encoding='utf-8').strip()

checks = {
    'version': version == '2.0.0-alpha83-secure-family-pairing',
    'version_code': 'versionCode = 144' in build,
    'version_name': '2.0.0-alpha83-secure-family-pairing' in build,
    'no_embedded_app_key_field': 'FAMILY_SYNC_APP_KEY' not in build and 'FAMILY_SYNC_APP_KEY' not in sync,
    'v2_register': '/v2/families/$familyCode/register' in sync,
    'v2_join': '/v2/families/$familyCode/join' in sync,
    'v2_sync': '/v2/families/$familyCode/sync' in sync,
    'bearer_auth': 'Authorization' in sync and 'Bearer $deviceToken' in sync,
    'token_storage': 'KEY_DEVICE_TOKEN' in sync and 'saveDeviceToken' in sync,
    'token_bound_to_family': 'KEY_TOKEN_FAMILY_CODE' in sync,
    'clear_token_on_family_change': 'clearDeviceTokenIfFamilyChanged' in store,
    'pairing_reopen': '/v2/families/$code/pairing/open' in sync,
    'settings_reopens_pairing': 'pairingManager.openPairingWindow()' in screen,
    'https_only': 'startsWith("https://")' in sync,
    'offline_local_preserved': 'repository.loadEntries()' in sync and 'repository.replaceEntries(merged)' in sync,
    'tombstones_preserved': 'tombstones(appContext)' in sync and 'deleted = true' in sync,
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ': ' + name)
if failed:
    raise SystemExit('Alpha83 contract failed: ' + ', '.join(failed))
print(f'Alpha83 secure family pairing contract: {len(checks)}/{len(checks)} PASS')
