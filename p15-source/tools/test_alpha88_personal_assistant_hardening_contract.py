#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
checks = []

def check(name, condition):
    checks.append((name, bool(condition)))

version = (ROOT / "VERSION").read_text(encoding="utf-8")
gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
home = (ROOT / "app/src/main/java/com/abfilepro/app/ui/screens/assistant/PersonalAssistantScreen.kt").read_text(encoding="utf-8")
markets = (ROOT / "app/src/main/java/com/abfilepro/app/ui/screens/assistant/MarketsOperationalScreen.kt").read_text(encoding="utf-8")
shopping = (ROOT / "app/src/main/java/com/abfilepro/app/assistant/ShoppingListSyncStore.kt").read_text(encoding="utf-8")
cloud = (ROOT / "app/src/main/java/com/abfilepro/app/assistant/FamilyCloudSync.kt").read_text(encoding="utf-8")
shop_ui = (ROOT / "app/src/main/java/com/abfilepro/app/ui/screens/assistant/ShoppingListOperationalScreen.kt").read_text(encoding="utf-8")
meetings = (ROOT / "app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantMeetingsScreen.kt").read_text(encoding="utf-8")
chat = (ROOT / "app/src/main/java/com/abfilepro/app/ui/screens/assistant/FamilyChatScreen.kt").read_text(encoding="utf-8")
presentation = (ROOT / "app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantPresentation.kt").read_text(encoding="utf-8")

check("version name", "2.0.0-alpha88-assistant-hardening" in version and 'versionName = "2.0.0-alpha88-assistant-hardening"' in gradle)
check("version code", "versionCode = 149" in gradle)
check("Arabic raster home bypass removed", 'if (language.code == "ar") {\n        AssistantReferenceHome' not in home)
check("native quick add", "showQuickAdd" in home and "quickModule" in home and "repository.add(entries, quickModule" in home)
check("assistant polling relaxed", "delay(15_000)" in home and "delay(3_000)" not in home)
check("chat polling relaxed", "delay(5_000)" in chat and "delay(1_500)" not in chat)
check("currency reference label", 'label = "سعر مرجعي"' in markets and 'PriceBox(label = "بيع"' not in markets and 'PriceBox(label = "شراء"' not in markets)
check("shopping starts empty", "if (raw == null)" not in shopping or "return emptyList()" in shopping)
check("legacy demo cleanup guarded", "isUntouchedLegacyDemo" in shopping and "alpha88_demo_migrated" in shopping)
check("shopping cloud IDs", 'ITEM_PREFIX = "shopping:item:"' in shopping and "shoppingStore.applyRemote" in cloud)
check("shopping sync outbound", "shoppingStore.itemPayload" in cloud and "metaForCloud" in cloud)
check("shopping UI shared store", "ShoppingListSyncStore" in shop_ui and "delay(5_000)" in shop_ui)
check("shopping keeps family request linkage", "if (module == AssistantModule.SHOPPING) entries else entries.filter" in home)
check("meeting edit/delete", "onEdit" in meetings and "onDelete" in meetings and "deleteMeeting" in meetings)
check("all meeting attachments visible", "recordings.forEachIndexed" in meetings and "attachments.forEachIndexed" in meetings)
check("truthful store-search title", 'copy("البحث في المتاجر", "Store search"' in presentation)
check("documents remain local-only", "AssistantModule.DOCUMENTS" not in cloud)

failed = [name for name, ok in checks if not ok]
for name, ok in checks:
    print(("PASS" if ok else "FAIL") + " - " + name)
print(f"RESULT: {len(checks)-len(failed)}/{len(checks)} PASS")
if failed:
    raise SystemExit(1)
