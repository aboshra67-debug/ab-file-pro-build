# Alpha51 Personal Assistant Implementation Plan

> Execution mode: inline implementation approved by the product owner. Preserve every working Alpha50 scanner, storage, and file-manager flow.

**Goal:** Replace the personal-assistant preview sheet with a production-usable, offline-first assistant hub containing the 16 approved modules, local persistence, prayer times/alerts, safe system integrations, and an installable signed debug APK.

**Architecture:** Keep navigation ownership inside `HomeScreen` so `MainActivity`, `FilesScreen`, and `FileUtils` remain byte-for-byte unchanged. A dedicated Compose feature package owns the assistant UI. Pure Kotlin domain classes own module definitions, calculator logic, prayer-time calculation, and validation. Android adapters own SharedPreferences persistence, alarms/notifications, device intents, and optional location access. External payments, shopping sites, and market sources are opened through explicit safe intents; no payment credentials are collected.

**Tech stack:** Kotlin 2.x, Jetpack Compose Material 3, Android SharedPreferences + JSON, AlarmManager/NotificationManager, platform intents, JUnit 4.

---

## Task 1: Lock the Alpha50 safety boundary

**Files:**
- Add: `tools/test_alpha51_personal_assistant_contract.py`
- Add: `app/src/test/java/com/abfilepro/app/assistant/AssistantDomainTest.kt`
- Add: `app/src/test/java/com/abfilepro/app/assistant/PrayerTimesCalculatorTest.kt`

1. Write failing contract assertions for version 112/Alpha51, the 16 module IDs, full-screen Home integration, required receivers/permissions, and unchanged hashes for `MainActivity.kt`, `FilesScreen.kt`, and `FileUtils.kt`.
2. Write failing unit tests for expression evaluation, module filtering, stable storage encoding, prayer ordering, and minute adjustments.
3. Run the Alpha51 contract and confirm the expected failures before implementation.

## Task 2: Implement the offline domain and storage layer

**Files:**
- Add: `app/src/main/java/com/abfilepro/app/assistant/AssistantModels.kt`
- Add: `app/src/main/java/com/abfilepro/app/assistant/AssistantDomain.kt`
- Add: `app/src/main/java/com/abfilepro/app/assistant/PrayerTimesCalculator.kt`
- Add: `app/src/main/java/com/abfilepro/app/assistant/AssistantRepository.kt`

1. Define Personal/Work categorisation, 16 stable module IDs, reusable local entries, prayer settings, and daily summaries.
2. Implement a bounded calculator parser with no script evaluation.
3. Implement local prayer calculation with city presets, calculation methods, Asr school, and per-prayer adjustment.
4. Implement JSON persistence with schema versioning and malformed-data recovery.
5. Run focused unit tests.

## Task 3: Implement prayer alerts and resilient scheduling

**Files:**
- Add: `app/src/main/java/com/abfilepro/app/assistant/PrayerAlarmScheduler.kt`
- Add: `app/src/main/java/com/abfilepro/app/assistant/PrayerAlarmReceiver.kt`
- Add: `app/src/main/java/com/abfilepro/app/assistant/PrayerRescheduleReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml`

1. Add notification, boot, time-change, and optional exact-alarm declarations.
2. Schedule each enabled prayer and pre-alert using exact alarms only when the user enables them and Android permits them; otherwise use safe inexact alarms.
3. Recompute alarms after boot, date, time, or timezone changes.
4. Keep all notification copy local and avoid background network access.

## Task 4: Build the full assistant hub and all module screens

**Files:**
- Add: `app/src/main/java/com/abfilepro/app/ui/screens/assistant/PersonalAssistantScreen.kt`
- Add: `app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantModuleScreens.kt`
- Add: `app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantComponents.kt`
- Add: `app/src/main/res/values/strings_alpha51.xml`
- Modify: `app/src/main/java/com/abfilepro/app/ui/screens/HomeScreen.kt`

1. Build an Arabic-first Today summary, All/Personal/Work filters, prominent prayer card, and a two-column grid for all approved modules.
2. Implement persistent CRUD screens for notes, tasks, appointments, meetings, money, shopping, family requests, documents, and medications.
3. Implement platform-backed voice recording, camera/gallery handoff, calendar insertion, and share flows.
4. Implement a safe calculator, payment-app discovery/launch, product comparison searches, and currency/gold source links.
5. Add back handling so the assistant behaves as a full page without changing app-level navigation.

## Task 5: Version, regression verification, and packaging

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `VERSION`
- Add: `PATCH_REPORT_V2_ALPHA51_PERSONAL_ASSISTANT.txt`

1. Set versionCode 112 and versionName `2.0.0-alpha51-personal-assistant`.
2. Run the Alpha51 contract, JUnit suite, and relevant current Alpha50 scanner/save contracts.
3. Build `assembleDebug` with JDK 17, Gradle 8.11.1, and Android SDK 36 using the existing test signing configuration.
4. Verify APK package/version/ABI/signature, calculate SHA-256, and smoke-install when an emulator/device is available.
5. Package a clean Safe Source ZIP that excludes build caches, private keystores, and generated APKs; deliver ZIP + APK + SHA-256 together.
