#!/usr/bin/env python3
"""Alpha52 PDF visual-match and regression contract."""
from hashlib import sha256
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def text(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")

def digest(path: str) -> str:
    return sha256((ROOT / path).read_bytes()).hexdigest()

gradle = text("app/build.gradle.kts")
assert 'versionCode = 113' in gradle
assert 'versionName = "2.0.0-alpha52-visual-match-pdf"' in gradle

home = text("app/src/main/java/com/abfilepro/app/ui/screens/assistant/PersonalAssistantScreen.kt")
modules = text("app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantModuleScreens.kt")
visual = text("app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantReferenceVisuals.kt")
presentation = text("app/src/main/java/com/abfilepro/app/ui/screens/assistant/AssistantPresentation.kt")

assert 'AssistantReferenceHome(' in home
assert 'AssistantReferenceModuleScreen(' in modules
assert 'language.code == "ar"' in home
assert 'language.code == "ar"' in modules
assert 'direct crops from the user-approved' in visual
assert '"الملاحظات"' in presentation
assert '"التقويم والمواعيد"' in presentation
assert '"الاجتماعات والمحاضر"' in presentation
assert '"التسجيل الصوتي"' in presentation
assert '"قائمة التسوق"' in presentation

assets = {
    "pa_ref_bills.webp": "6bd655010c43f6a052fda20d49cfa9851c711f95f031a5535c0b4188692517da",
    "pa_ref_calculator.webp": "800ec7a5c525a2bf83f835f77052a78b5ca28020f10477d5f4f0fa3df9e28e09",
    "pa_ref_calendar.webp": "68d8a773087784bf09b0e2a5579b89df77a8def61a2e9428a9a270a4c85ff1ce",
    "pa_ref_camera.webp": "dd9db417b3b9b70ef5ba7517168dbfc1f0ff7a10b3f4c10afd29e02f2034bf53",
    "pa_ref_currency.webp": "8281662e1a1ee99e150ce4a6bebd190d982a5198864a5d06266428403d563af4",
    "pa_ref_documents.webp": "f0e40b088b516671984060dc95c12d9f0f51ce891b61201b18cd8955f5493ab6",
    "pa_ref_family.webp": "0dd07aae4d43aaf926e1e79ec963a1a4ecb88fc1c7161e5b1b99ce0bc9ffdce4",
    "pa_ref_health.webp": "b5ded1245e37b0b7aa96257ce273dee8ed51c86b13fe61ca125278ba645f91a8",
    "pa_ref_home_more.webp": "76d9341ebe25d2e883bd421b0788ca6ef68504a512d4774a3439b8d1c6cb98c2",
    "pa_ref_home_top.webp": "2554570cde1d1c375c8e4f4e229efd2e7da6ef8af03c1a66a064ef20e51dc0bf",
    "pa_ref_meetings.webp": "f51d1a643523ff788188caf72f49a414bbf93c31d5c5c832d2ac0986e25def72",
    "pa_ref_money.webp": "0ccd4f5ab96f87e282bcc7eefbf061a66a3badaeb32b629ac41999c52d10a11e",
    "pa_ref_notes.webp": "a84279afdf4a0a8c3e61335ff9df681a9dd97e36ea3f52ce52bb1ba5b8270ea9",
    "pa_ref_prayer.webp": "5f8506d12160595057f98126fcdea52663e233dec10f6adf8175fef40b15f412",
    "pa_ref_prices.webp": "ce29bcc15d9cb20451c0c0364d393fbf4e22f3914ccfd95da1a1f9a32f79c271",
    "pa_ref_shopping.webp": "d0860e66124470d50a10e85fb5a003b24ebb1b5bee25d4a6dcf0d3be5ef6d671",
    "pa_ref_tasks.webp": "3a74067db9ef1646120bc38b2c4bc5c794a74ff36ce4350978d0ddbfc07d4a3e",
    "pa_ref_voice.webp": "5d38f8bbb987fbfefeeffb744fec92a0f948f158b2366f5dab9b753be08d3aa6",
}
for name, expected in assets.items():
    path = f"app/src/main/res/drawable-nodpi/{name}"
    assert (ROOT / path).is_file(), f"missing approved visual asset: {name}"
    assert digest(path) == expected, f"approved visual asset changed: {name}"

# Alpha51 protected scanner/storage files remain byte-identical.
protected = {
    "app/src/main/java/com/abfilepro/app/MainActivity.kt": "0085573df101ab665f43f8fda6ff008e66517484af9a3f2fce5e0cbd00288145",
    "app/src/main/java/com/abfilepro/app/ui/screens/FilesScreen.kt": "c7e8da7dcf2d39663b6705aa15abcd117e243c43995a36228cb2fca4d55e76e5",
    "app/src/main/java/com/abfilepro/app/core/FileUtils.kt": "8e4dfb3057ccb7effbf7ce7e63a5fa761c38e2c6f883ac9dab7819fb59e21d5e",
}
for path, expected in protected.items():
    assert digest(path) == expected, f"protected file changed: {path}"

print("ALPHA52_PERSONAL_ASSISTANT_VISUAL_CONTRACT: PASS")
