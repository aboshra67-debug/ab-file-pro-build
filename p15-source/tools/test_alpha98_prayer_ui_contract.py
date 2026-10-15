from pathlib import Path

root = Path(__file__).resolve().parents[1]
read = lambda p: (root / p).read_text(encoding='utf-8')

version = read('VERSION').strip()
gradle = read('app/build.gradle.kts')
manifest = read('app/src/main/AndroidManifest.xml')
models = read('app/src/main/java/com/abfilepro/app/assistant/AssistantModels.kt')
cloud = read('app/src/main/java/com/abfilepro/app/assistant/FamilyCloudSync.kt')
crypto = read('app/src/main/java/com/abfilepro/app/assistant/SecureLocalSecrets.kt')
chat = read('app/src/main/java/com/abfilepro/app/assistant/FamilyChatService.kt')
prayer = read('app/src/main/java/com/abfilepro/app/assistant/PrayerAlarmReceiver.kt')
assistant_ui = read('app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantModuleScreens.kt')
price_service = read('app/src/main/java/com/abfilepro/app/assistant/ProductPriceComparisonService.kt')
price_alerts = read('app/src/main/java/com/abfilepro/app/assistant/ProductPriceAlerts.kt')
server = read('family-sync-server/main.py')

modules = [
    'PRAYER(', 'NOTES(', 'TASKS(', 'CALENDAR(', 'MEETINGS(', 'VOICE(', 'CAMERA(',
    'CALCULATOR(', 'MONEY(', 'MARKETS(', 'BILLS(', 'COMPARE(', 'SHOPPING(',
    'FAMILY_REQUESTS(', 'DOCUMENTS(', 'HEALTH(',
]

checks = {
    'version': version == '2.0.0-alpha98-prayer-ui-reference-polish',
    'version_code': 'versionCode = 159' in gradle,
    'version_name': 'versionName = "2.0.0-alpha98-prayer-ui-reference-polish"' in gradle,
    'all_16_modules': all(x in models for x in modules),
    'backup_disabled': 'android:allowBackup="false"' in manifest,
    'cleartext_disabled': 'android:usesCleartextTraffic="false"' in manifest,
    'provider_not_exported': 'android:name="androidx.core.content.FileProvider"' in manifest and 'android:exported="false"' in manifest,
    'reschedule_receivers_not_exported': manifest.count('android:exported="false"') >= 7,
    'keystore_crypto': all(x in crypto for x in ['AndroidKeyStore', 'AES/GCM/NoPadding', 'KeyGenParameterSpec', 'PURPOSE_ENCRYPT', 'PURPOSE_DECRYPT']),
    'encrypted_token_key': 'KEY_DEVICE_TOKEN_ENCRYPTED' in cloud and 'SecureLocalSecrets.encrypt(token)' in cloud and 'SecureLocalSecrets.decrypt(encrypted)' in cloud,
    'plaintext_token_migration_only': '.remove(KEY_DEVICE_TOKEN)' in cloud and '.putString(KEY_DEVICE_TOKEN, token)' not in cloud,
    'https_only_family_base': 'it.startsWith("https://")' in cloud,
    'family_auth_v2_client': '/v2/families/$familyCode/sync' in cloud and '/v2/families/$code/pairing/open' in cloud,
    'family_chat_v2_client': '/v2/families/${session.familyCode}/chat/pull' in chat and '/v2/families/${session.familyCode}/chat/messages' in chat,
    'family_backend_v3_snapshot': 'version="3.0.0"' in server and 'familyAuthVersion": 2' in server and 'familyChatVersion": 1' in server,
    'backend_hashes_tokens': 'hashlib.sha256(token.encode("utf-8"))' in server and 'token_hash CHAR(64)' in server,
    'backend_pairing_rate_limit': 'check_join_rate' in server and 'too many pairing attempts' in server,
    'documents_local_only': 'AssistantModule.DOCUMENTS' not in cloud,
    'shopping_sync_preserved': 'ShoppingListSyncStore' in cloud,
    'health_sync_preserved': 'AssistantModule.HEALTH' in cloud,
    'price_comparison_preserved': all(x in price_service for x in ['AMAZON("أمازون مصر"', 'NOON("نون"', 'JUMIA("جوميا"']) and 'priceEgp = null' in price_service,
    'price_alerts_preserved': 'class ProductPriceAlertWorker' in price_alerts,
    'truthful_prayer_sound': 'ADHAN_CHANNEL_ID' in prayer and 'DEFAULT_ALARM_ALERT_URI' in prayer and 'المؤذن المختار' not in prayer,
    'fake_reciter_selector_removed': 'label = { Text("المنشاوي") }' not in assistant_ui and 'label = { Text("عبد الباسط") }' not in assistant_ui,
    'no_tmp_backup': not any(root.rglob('*.tmp')),
    'reference_mosque_illustration': (root / 'app/src/main/res/drawable-nodpi/prayer_mosque_banner.png').is_file(),
    'prayer_screen_only_new_layout': 'private fun PrayerModuleScreen(' in assistant_ui and 'PrayerUiSectionHeading(' in assistant_ui and 'private fun PrayerUiSectionHeading(' in assistant_ui,
    'real_next_prayer_card': 'val nextPrayer = PrayerName.entries.filter' in assistant_ui and 'prayerLabel(nextPrayer, language)' in assistant_ui,
    'countdown_is_from_local_prayer_time': 'java.time.Duration.between(clock, ZonedDateTime.of(nextDate' in assistant_ui,
    'six_city_presets': 'CityPreset.ALL.chunked(3)' in assistant_ui,
    'gps_location_permissions_still_requested': 'ActivityResultContracts.RequestMultiplePermissions()' in assistant_ui and 'refreshPrayerGpsLocation(context, applyGpsLocation)' in assistant_ui,
    'automatic_gps_switch': 'checked = settings.autoGps' in assistant_ui,
    'real_alarm_toggle': 'checked = enabled' in assistant_ui and 'settings.enabledAlerts + prayer' in assistant_ui,
    'prayer_minutes_plus_minus': 'adjustment - 1' in assistant_ui and 'adjustment + 1' in assistant_ui,
    'prayer_method_switch': 'settings.copy(method = method)' in assistant_ui,
    'follow_up_intervals_preserved': 'settings.copy(prayerCheckMinutes = minutes)' in assistant_ui and 'settings.copy(repeatNudgeMinutes = minutes)' in assistant_ui,
    'no_other_screen_removed': 'private fun ExternalActionCard(' in assistant_ui and 'private fun pickDateTime(' in assistant_ui,
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(f"[{'PASS' if ok else 'FAIL'}] {name}")
if failed:
    raise SystemExit('Failed: ' + ', '.join(failed))
print(f"Alpha98 prayer-screen contract: {len(checks)}/{len(checks)} PASS")
