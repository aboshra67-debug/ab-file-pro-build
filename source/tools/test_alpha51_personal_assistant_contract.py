#!/usr/bin/env python3
"""Alpha51 feature and regression contract.

This test intentionally checks source-level boundaries that ordinary unit tests cannot:
the three scanner/storage files declared protected for this patch must stay byte-identical.
"""
from hashlib import sha256
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def text(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def digest(path: str) -> str:
    return sha256((ROOT / path).read_bytes()).hexdigest()


gradle = text("app/build.gradle.kts")
manifest = text("app/src/main/AndroidManifest.xml")
home = text("app/src/main/java/com/abfilepro/app/ui/screens/HomeScreen.kt")

assert 'versionCode = 112' in gradle
assert 'versionName = "2.0.0-alpha51-personal-assistant"' in gradle
assert 'PersonalAssistantScreen(' in home
assert 'HomeSheet.ASSISTANT' not in home

models = text("app/src/main/java/com/abfilepro/app/assistant/AssistantModels.kt")
for module_id in (
    "PRAYER", "NOTES", "TASKS", "CALENDAR", "MEETINGS", "VOICE",
    "CAMERA", "CALCULATOR", "MONEY", "MARKETS", "BILLS", "COMPARE",
    "SHOPPING", "FAMILY_REQUESTS", "DOCUMENTS", "HEALTH",
):
    assert module_id in models, f"missing module: {module_id}"

for required_file in (
    "app/src/main/java/com/abfilepro/app/assistant/PrayerTimesCalculator.kt",
    "app/src/main/java/com/abfilepro/app/assistant/AssistantRepository.kt",
    "app/src/main/java/com/abfilepro/app/assistant/PrayerAlarmScheduler.kt",
    "app/src/main/java/com/abfilepro/app/ui/screens/assistant/PersonalAssistantScreen.kt",
    "app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantModuleScreens.kt",
):
    assert (ROOT / required_file).is_file(), f"missing file: {required_file}"

assert "android.permission.POST_NOTIFICATIONS" in manifest
assert "android.permission.RECEIVE_BOOT_COMPLETED" in manifest
assert ".assistant.PrayerAlarmReceiver" in manifest
assert ".assistant.PrayerRescheduleReceiver" in manifest

protected = {
    "app/src/main/java/com/abfilepro/app/MainActivity.kt": "0085573df101ab665f43f8fda6ff008e66517484af9a3f2fce5e0cbd00288145",
    "app/src/main/java/com/abfilepro/app/ui/screens/FilesScreen.kt": "c7e8da7dcf2d39663b6705aa15abcd117e243c43995a36228cb2fca4d55e76e5",
    "app/src/main/java/com/abfilepro/app/core/FileUtils.kt": "8e4dfb3057ccb7effbf7ce7e63a5fa761c38e2c6f883ac9dab7819fb59e21d5e",
}
for path, expected in protected.items():
    assert digest(path) == expected, f"protected file changed: {path}"

print("ALPHA51_PERSONAL_ASSISTANT_CONTRACT: PASS")
