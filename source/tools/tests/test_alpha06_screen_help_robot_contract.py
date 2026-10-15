from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
COMPONENT = ROOT / 'app/src/main/java/com/abfilepro/app/ui/components/ScreenHelpRobot.kt'
SCAN = ROOT / 'app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt'
CONVERT = ROOT / 'app/src/main/java/com/abfilepro/app/ui/screens/ConvertScreen.kt'
STRINGS = ROOT / 'app/src/main/res/values/strings_v206_screen_help.xml'

assert COMPONENT.exists(), 'ScreenHelpRobot component is missing'
component = COMPONENT.read_text(encoding='utf-8')
assert 'show_ai_help' in component, 'helper must honor the global show_ai_help preference'
assert 'ic_ab_help_robot' in component, 'helper must use the real vector robot icon'
assert 'ModalBottomSheet' in component, 'helper explanation must open as an in-app sheet'
assert 'fun ScreenHelpRobot' in component, 'shared helper composable is missing'

scan = SCAN.read_text(encoding='utf-8')
convert = CONVERT.read_text(encoding='utf-8')
assert 'ScreenHelpRobot(' in scan, 'Scanner does not include the helper robot'
assert 'help_scanner_title' in scan, 'Scanner does not bind scanner-specific help copy'
assert 'ScreenHelpRobot(' in convert, 'Converter does not include the helper robot'
assert 'help_convert_title' in convert, 'Converter does not bind converter-specific help copy'

assert STRINGS.exists(), 'Alpha06 helper strings file is missing'
root = ET.parse(STRINGS).getroot()
names = {node.attrib.get('name') for node in root.findall('string')}
for key in ('help_scanner_title', 'help_scanner_body', 'help_convert_title', 'help_convert_body', 'screen_help_close'):
    for lang in ('ar', 'en', 'fr'):
        assert f'{key}_{lang}' in names, f'missing {key}_{lang}'

print('alpha06 screen help robot contract: PASS')
