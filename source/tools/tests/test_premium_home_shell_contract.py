from pathlib import Path

root = Path(__file__).resolve().parents[2]
home = (root / 'app/src/main/java/com/abfilepro/app/ui/screens/HomeScreen.kt').read_text(encoding='utf-8')
settings = (root / 'app/src/main/java/com/abfilepro/app/ui/screens/SettingsScreen.kt').read_text(encoding='utf-8')
toolcard = (root / 'app/src/main/java/com/abfilepro/app/ui/components/ToolCard.kt').read_text(encoding='utf-8')
theme = (root / 'app/src/main/java/com/abfilepro/app/ui/theme/Theme.kt').read_text(encoding='utf-8')
strings = (root / 'app/src/main/res/values/strings_v205_premium_home.xml').read_text(encoding='utf-8') if (root / 'app/src/main/res/values/strings_v205_premium_home.xml').exists() else ''

# Main hub: five intentional destinations only.
for needle in [
    '"files",',
    '"scan",',
    '"convert",',
    'home_personal_assistant',
    'home_ai_coming_soon',
]:
    assert needle in home, needle

# Language picker is available from the home header and writes the same preference MainActivity reads.
assert 'AppLanguage.entries.forEach' in home
assert 'putString("language", option.code)' in home
assert 'painterResource(R.drawable.ic_ab_language)' in home
assert 'painterResource(R.drawable.ic_ab_settings)' in home

# Seven-day launch intro with a persistent opt-out.
assert 'INTRO_WINDOW_MS' in home
assert '7L * 24L * 60L * 60L * 1000L' in home
assert 'intro_disabled' in home
assert 'first_launch_at' in home

# Small floating robot helper can be disabled globally.
assert 'SmallFloatingActionButton' in home
assert 'painterResource(R.drawable.ic_ab_help_robot)' in home
assert 'show_ai_help' in home

# Settings exposes the agreed controls and multiple themes including red/yellow.
for needle in ['intro_disabled', 'show_ai_help', 'theme_preset', 'red', 'amber', 'blue', 'green', 'purple', 'navy', 'light', 'amoled', 'graphite']:
    assert needle in settings or needle in theme, needle

# Shared visual language upgrades screens/buttons without changing feature logic.
assert 'BorderStroke' in toolcard
assert 'RoundedCornerShape(24.dp)' in toolcard
assert 'Shapes(' in theme

# Three-language copy exists for core home actions.
for key in [
    'home_file_manager_ar', 'home_file_manager_en', 'home_file_manager_fr',
    'home_scanner_ar', 'home_scanner_en', 'home_scanner_fr',
    'home_convert_ar', 'home_convert_en', 'home_convert_fr',
    'home_personal_assistant_ar', 'home_personal_assistant_en', 'home_personal_assistant_fr',
    'home_ai_coming_soon_ar', 'home_ai_coming_soon_en', 'home_ai_coming_soon_fr',
]:
    assert key in strings, key

print('premium home shell contract: PASS')
