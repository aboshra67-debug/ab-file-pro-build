"""Black-box Scanner regression tests: capture, edit, save and inspect real PDFs."""
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import traceback
import xml.etree.ElementTree as ET
from pathlib import Path

import uiautomator2 as u2
from PIL import Image, ImageDraw
from pypdf import PdfReader
from p11_jdwp_crop_gate import CropCommitGate

PKG = os.environ.get('SCANNER_TEST_PACKAGE', 'com.abfilepro.app.p10batchtrial')
OUT = Path('runtime-evidence')
OUT.mkdir(exist_ok=True)
RESULTS = []
CASE = ''
DIGITS = str.maketrans('٠١٢٣٤٥٦٧٨٩', '0123456789')
STRINGS = {}
for p in Path('project/app/src/main/res/values').glob('*.xml'):
    try:
        for item in ET.parse(p).getroot():
            if item.tag == 'string':
                STRINGS[item.attrib.get('name')] = ''.join(item.itertext())
    except ET.ParseError:
        pass

def t(key):
    return STRINGS.get(key + '_ar', STRINGS.get(key, key))

def adb(*args, check=True):
    r = subprocess.run(['adb', *args], text=True, capture_output=True, timeout=60)
    if check and r.returncode:
        raise RuntimeError(r.stderr + r.stdout)
    return r.stdout

d = u2.connect()
d.settings['wait_timeout'] = 8

def dump():
    return d.dump_hierarchy(compressed=False)

def nodes():
    return list(ET.fromstring(dump()).iter('node'))

def center(n):
    v = list(map(int, re.findall(r'\d+', n.attrib['bounds'])))
    return (v[0] + v[2]) // 2, (v[1] + v[3]) // 2

def find(value, partial=False):
    value = value.translate(DIGITS).casefold()
    for n in nodes():
        for attr in ('text', 'content-desc'):
            s = n.attrib.get(attr, '').translate(DIGITS).casefold()
            if (value in s if partial else value == s) and n.attrib.get('enabled') != 'false':
                return n
    return None

def click(value, partial=False, timeout=20):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        n = find(value, partial)
        if n is not None:
            d.click(*center(n))
            time.sleep(.45)
            return
        time.sleep(.35)
    raise TimeoutError('Cannot click: ' + value)

def wait(value, partial=False, timeout=35):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        n = find(value, partial)
        if n is not None:
            return n
        time.sleep(.4)
    raise TimeoutError('Missing screen/text: ' + value)

def snapshot(name):
    name = CASE + '_' + name if CASE else name
    try:
        (OUT / (name + '.xml')).write_text(dump(), encoding='utf-8')
        d.screenshot(str(OUT / (name + '.png')))
    except Exception as e:
        print('SNAPSHOT_ERROR', str(e), flush=True)

def texts():
    return [n.attrib.get('text', '').translate(DIGITS) for n in nodes() if n.attrib.get('text')]

def start_mode(mode):
    adb('logcat', '-c', check=False)
    adb('shell', 'am', 'force-stop', PKG)
    adb('shell', 'pm', 'clear', PKG)
    adb('shell', 'pm', 'grant', PKG, 'android.permission.CAMERA')
    adb('shell', 'appops', 'set', PKG, 'MANAGE_EXTERNAL_STORAGE', 'allow')
    adb('shell', 'am', 'start', '-n', PKG + '/com.abfilepro.app.MainActivity')
    end = time.monotonic() + 50
    while time.monotonic() < end:
        # The first launch asks Android to pin a workspace shortcut. Dismiss
        # this system modal before looking for Compose home screen semantics.
        if find('Add to Home screen') is not None:
            click('Cancel', timeout=3)
            continue
        transient = find('تخطي')
        if transient is None:
            transient = find('لاحقًا')
        if transient is not None:
            d.click(*center(transient))
            time.sleep(.6)
            continue
        entry = find('Scanner المسح الضوئي')
        if entry is not None:
            d.click(*center(entry))
            time.sleep(.6)
            break
        time.sleep(.5)
    else:
        snapshot('startup_failed')
        raise TimeoutError('Scanner home entry unavailable')
    click(t('alpha11_scan_' + mode), timeout=25)
    wait('Scanner Pro', partial=True)
    for n in nodes():
        if n.attrib.get('checkable') == 'true' and n.attrib.get('checked') == 'true':
            d.click(*center(n))
            time.sleep(.4)
            break
    # CameraX retries validation on an emulator with only a back camera.
    # The shutter appears before initialization completes; wait for the
    # actual Camera2 open event rather than treating its presence as ready.
    end = time.monotonic() + 30
    while time.monotonic() < end:
        app_pid = adb('shell', 'pidof', PKG, check=False).strip().split()
        camera_log = adb('logcat', '-d', '--pid=' + app_pid[0], '-s', 'Camera2CameraImpl:D', '*:S') if app_pid else ''
        if 'CameraDevice.onOpened()' in camera_log:
            time.sleep(1.5)
            print('P10_STEP camera_ready ' + mode, flush=True)
            return
        time.sleep(.5)
    raise TimeoutError('Camera2 did not open within 30s')

def capture(count):
    for i in range(count):
        time.sleep(2.5)
        print('P10_STEP capture ' + str(i + 1), flush=True)
        click(t('scanner_pro_camera_016'), timeout=35)
        wait(t('alpha24_capture_success'), timeout=45)
        click(t('alpha50_next_page'), timeout=20)
        time.sleep(.7)

def count_camera():
    for s in texts():
        if 'الصفحات' in s and '/' in s:
            found = re.findall(r'\d+', s)
            if found:
                return int(found[0])
    raise AssertionError('Camera page count not visible: ' + repr(texts()))

def count_review():
    for s in texts():
        m = re.search(r'الصفحة\s+(\d+)\s+من\s+(\d+)', s)
        if m:
            return int(m.group(2))
    raise AssertionError('Review page count not visible: ' + repr(texts()))

def process_review():
    click('حفظ ومراجعة', partial=True)
    end = time.monotonic() + 180
    stages = []
    while time.monotonic() < end:
        assert find('اختر المهمة') is None, 'Final review returned to Scanner hub before saving the document'
        if find(t('scan_screen_029')) is not None:
            return count_review(), stages
        if find(t('alpha20_bounds_title')) is not None:
            n = find(t('alpha20_continue'))
            if n is not None:
                stages.append('bounds')
                d.click(*center(n))
                time.sleep(.6)
                continue
        if find(t('alpha34_save_continue')) is not None:
            stages.append('editor')
            snapshot('editor_' + str(len(stages)))
            click(t('alpha34_save_continue'))
            time.sleep(.6)
            continue
        time.sleep(.4)
    raise TimeoutError('Batch review did not reach final review: ' + repr(texts()))

def pdfs():
    return set(adb('shell', 'find /storage/emulated/0 -type f -name "*.pdf" 2>/dev/null', check=False).splitlines())

def save_pdf(tag, action=None):
    before = pdfs()
    click(action or t('scan_screen_036'))
    end = time.monotonic() + 60
    while time.monotonic() < end:
        created = pdfs() - before
        if created:
            remote = sorted(created)[0]
            local = OUT / (tag + '.pdf')
            adb('pull', remote, str(local))
            time.sleep(.8)
            return local, len(PdfReader(local).pages)
        time.sleep(.5)
    raise TimeoutError('No new final PDF found: ' + repr(texts()))

def import_images(ids):
    # A cold DocumentsUI process avoids its Android 11 stale-adapter crash
    # between isolated cases; the application's import path is unchanged.
    adb('shell', 'am', 'force-stop', 'com.google.android.documentsui')
    click(t('scanner_pro_camera_015'))
    time.sleep(1.5)
    for desc in ('Show roots', 'Open navigation drawer'):
        if find(desc) is not None:
            click(desc)
            break
    if find('Downloads') is not None:
        click('Downloads')
    time.sleep(.7)
    # DocumentsUI can retain the previous scroll position between imports.
    # Start at the top before looking for the next fixture selection.
    for _ in range(3):
        d.swipe_ext('down', scale=.7)
        time.sleep(.15)
    for index, page_id in enumerate(ids):
        value = 'P10_%02d.png' % page_id
        n = find(value, partial=True)
        for _ in range(8):
            if n is not None:
                break
            d.swipe_ext('up', scale=.55)
            time.sleep(.35)
            n = find(value, partial=True)
        if n is None:
            snapshot('picker_failed')
            raise TimeoutError('Fixture not found in picker: ' + value)
        if index == 0:
            d.long_click(*center(n), duration=1)
        else:
            d.click(*center(n))
        time.sleep(.3)
    snapshot('picker_selected_' + str(len(ids)))
    for label in ('Open', 'Select', 'Done'):
        if find(label) is not None:
            click(label)
            break
    else:
        raise TimeoutError('Picker confirm control unavailable: ' + repr(texts()))
    time.sleep(2)

def scroll_click(value):
    for _ in range(16):
        if find(value, partial=True) is not None:
            click(value, partial=True)
            return
        d.swipe_ext('up', scale=.65)
        time.sleep(.4)
    raise TimeoutError('Scroll control not found: ' + value)

def run_case(name, fn):
    global CASE
    CASE = name
    started = time.monotonic()
    try:
        observations = fn() or {}
        result = {'name': name, 'status': 'PASS', 'observations': observations}
    except AssertionError as e:
        result = {'name': name, 'status': 'FAIL', 'error': str(e)}
        snapshot(name + '_failure')
    except Exception as e:
        result = {'name': name, 'status': 'BLOCKED', 'error': repr(e), 'traceback': traceback.format_exc()}
        snapshot(name + '_blocked')
    result['seconds'] = round(time.monotonic() - started, 2)
    RESULTS.append(result)
    (OUT / (name + '_logcat.txt')).write_text(adb('logcat', '-d', '-v', 'threadtime', check=False), encoding='utf-8')
    (OUT / 'runtime_results.json').write_text(json.dumps(RESULTS, ensure_ascii=False, indent=2), encoding='utf-8')
    print('P10_CASE ' + json.dumps(result, ensure_ascii=False), flush=True)

fixture_dir = Path('fixtures')
fixture_dir.mkdir(exist_ok=True)
for i in range(1, 11):
    im = Image.new('RGB', (960, 1280), 'white')
    pen = ImageDraw.Draw(im)
    pen.rectangle((20, 20, 939, 1259), outline='black', width=4)
    pen.text((180, 200), 'P10 TEST PAGE %02d' % i, fill='black', font_size=48)
    for j in range(i):
        x = 110 + j * 70
        pen.rectangle((x, 690, x + 30, 950), fill='black')
    path = fixture_dir / ('P10_%02d.png' % i)
    im.save(path)
    adb('push', str(path), '/sdcard/Download/' + path.name)
    adb('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', 'file:///sdcard/Download/' + path.name, check=False)

def capture_save_three():
    start_mode('batch')
    capture(3)
    assert count_camera() == 3, 'Three captures were not retained'
    actual, stages = process_review()
    snapshot('capture3_final_review')
    assert actual == 3, 'Expected review 3 pages; got %s' % actual
    pdf, count = save_pdf('capture3_saved')
    snapshot('capture3_save_success')
    assert count == 3, 'Saved PDF has %s pages, expected 3' % count
    assert find('اختر المهمة') is None, 'Successful batch save returned to Scanner hub before New Scan'
    click(t('alpha15_save_new_scan'))
    wait('دفعة متعددة', partial=True)
    assert count_camera() == 0, 'New batch retains old captures'
    return {'review_pages': actual, 'pdf_pages': count, 'restart_mode': 'BATCH', 'stages': stages}

def import_three():
    start_mode('batch')
    import_images([1, 2, 3])
    actual_camera = count_camera()
    snapshot('import3_camera')
    assert actual_camera == 3, 'Selected 3 gallery images, but BATCH camera reports %s and cannot review the imported pages' % actual_camera
    actual, stages = process_review()
    pdf, count = save_pdf('import3_saved')
    snapshot('import3_saved_result')
    assert actual == 3 and count == 3, 'Imported 3 images; review=%s, saved PDF=%s pages, stages=%s' % (actual, count, stages)
    return {'review_pages': actual, 'pdf_pages': count}

def import_ten():
    start_mode('batch')
    import_images(list(range(1, 11)))
    wait(t('scan_screen_029'), timeout=40)
    actual = count_review()
    pdf, count = save_pdf('import10_saved')
    snapshot('import10_saved_result')
    assert actual == 10 and count == 10, 'Imported 10 images; review=%s, PDF=%s' % (actual, count)
    bar_counts = pdf_page_markers(pdf)
    assert bar_counts == list(range(1,11)), 'Saved PDF page order/bar markers: %s' % bar_counts
    return {'review_pages': actual, 'pdf_pages': count, 'page_order': bar_counts}

def pdf_page_markers(pdf):
    bar_counts = []
    for page in PdfReader(pdf).pages:
        im = page.images[0].image.convert('L')
        row = [im.getpixel((x, int(im.height * .62))) < 70 for x in range(im.width)]
        widths, width = [], 0
        for black in row + [False]:
            if black:
                width += 1
            elif width:
                widths.append(width)
                width = 0
        bar_counts.append(sum(w >= 10 for w in widths))
    return bar_counts

def retake_replaces_page():
    start_mode('batch')
    capture(2)
    actual, _ = process_review()
    assert actual == 2
    scroll_click(t('scan_screen_046'))
    wait('Scanner Pro', partial=True)
    capture(1)
    actual = count_camera()
    snapshot('batch_retake_camera')
    assert actual == 2, 'Retaking one of 2 pages must replace it; camera now reports %s pages' % actual
    reviewed, stages = process_review()
    pdf, count = save_pdf('retake2_saved')
    assert reviewed == 2 and count == 2, 'Retake lost a page while reviewing/saving: review=%s PDF=%s' % (reviewed, count)
    return {'camera_pages': actual, 'pdf_pages': count, 'stages': stages}

def add_after_five():
    start_mode('batch')
    capture(5)
    actual, _ = process_review()
    assert actual == 5, 'Expected first review 5 pages; got %s' % actual
    scroll_click(t('scan_screen_034'))
    assert count_camera() == 5, 'Existing five pages disappeared on add'
    capture(1)
    actual = count_camera()
    snapshot('add_after5_camera')
    assert actual == 6, 'After adding a sixth page, count is %s instead of 6' % actual
    reviewed, stages = process_review()
    pdf, count = save_pdf('add6_saved')
    assert reviewed == 6 and count == 6, 'Adding a sixth page lost existing pages: review=%s PDF=%s' % (reviewed, count)
    return {'camera_pages': actual, 'pdf_pages': count, 'stages': stages}

def edit_bounds_batch():
    start_mode('batch')
    capture(2)
    actual, _ = process_review()
    assert actual == 2
    scroll_click(t('alpha20_bounds_title'))
    time.sleep(1)
    snapshot('edit_bounds_batch_clicked')
    assert find(t('scan_screen_029')) is None, 'Bounds button leaves BATCH in review without opening adjustment'
    wait(t('alpha20_bounds_title'))
    click(t('alpha20_continue'))
    wait(t('alpha34_save_continue'))
    click(t('alpha34_save_continue'))
    wait(t('scan_screen_029'))
    actual = count_review()
    pdf, count = save_pdf('bounds2_saved')
    assert actual == 2 and count == 2, 'Bounds edit lost another batch page: review=%s PDF=%s' % (actual, count)
    return {'bounds_opened': True, 'pdf_pages': count}

def card_one_side():
    start_mode('id')
    click(t('scanner_pro_camera_016'))
    wait('مراجعة وحفظ', partial=True, timeout=45)
    click('مراجعة وحفظ', partial=True)
    wait(t('scan_screen_029'))
    snapshot('card_one_side_review')
    before = pdfs()
    click(t('scan_screen_036'))
    end = time.monotonic() + 10
    while time.monotonic() < end:
        assert not (pdfs() - before), 'Two-sided card saved with only one capture; no missing-side confirmation'
        if find('الوجه الثاني', partial=True) is not None:
            snapshot('card_missing_back_guard')
            click(t('scan_screen_052'))
            assert count_review() == 1, 'Dismissing the missing-side prompt lost the first capture'
            click(t('scan_screen_036'))
            wait('تصوير الوجه الثاني')
            click('تصوير الوجه الثاني')
            wait('Scanner Pro', partial=True)
            assert count_camera() == 1, 'Continuing the card capture lost its first side'
            time.sleep(2.5)
            click(t('scanner_pro_camera_016'))
            wait(t('scan_screen_029'), timeout=45)
            assert count_review() == 2
            pdf, count = save_pdf('card_guard_completed_saved')
            assert count == 1, 'Completing the second side did not produce a combined single-page PDF'
            return {'missing_side_prompt': True, 'first_side_preserved': True, 'completed_pdf_pages': count}
        time.sleep(.4)
    raise AssertionError('Saving a one-sided card did not prompt for the missing second side')

def card_two_sides():
    start_mode('id')
    click(t('scanner_pro_camera_016'))
    wait('مراجعة وحفظ', partial=True, timeout=45)
    click(t('scanner_pro_camera_016'))
    wait(t('scan_screen_029'), timeout=45)
    assert count_review() == 2, 'Two card sides were not retained'
    pdf, count = save_pdf('card_two_sides_saved')
    snapshot('card_two_sides_saved')
    assert count == 1, 'Combined card must have one PDF page; actual=%s' % count
    return {'captured_sides': 2, 'pdf_pages': count}

def document_save_pdf():
    start_mode('document')
    click(t('scanner_pro_camera_016'))
    wait(t('alpha24_capture_success'), timeout=45)
    click(t('alpha20_continue'))
    wait(t('alpha20_bounds_title'))
    click(t('alpha20_continue'))
    wait(t('alpha34_save_continue'))
    click(t('alpha34_save_continue'))
    wait(t('alpha36_save_now'))
    snapshot('document_save_options')
    pdf, count = save_pdf('document_saved', t('alpha36_save_now'))
    assert count == 1, 'Single-document save regression: PDF pages=%s' % count
    assert len(PdfReader(pdf).pages[0].images) >= 1, 'Single-document PDF has no captured image'
    snapshot('document_saved_result')
    return {'pdf_pages': count, 'save_options_available': True}

def gallery_retake_preserves_siblings():
    start_mode('batch')
    import_images([1, 2, 3])
    actual, _ = process_review()
    assert actual == 3
    scroll_click(t('alpha20_bounds_title'))
    assert find(t('scan_screen_029')) is None, 'Gallery batch bounds button did not open adjustment'
    wait(t('alpha20_bounds_title'))
    time.sleep(1)
    click(t('alpha20_retake'))
    wait('Scanner Pro', partial=True)
    capture(1)
    assert count_camera() == 3, 'Retake from bounds changed the three-page batch size'
    actual, stages = process_review()
    pdf, count = save_pdf('gallery_bounds_retake_saved')
    assert actual == 3 and count == 3, 'Retake erased sibling gallery pages: review=%s PDF=%s' % (actual, count)
    markers = pdf_page_markers(pdf)
    assert markers[1:] == [2, 3], 'Retake lost or reordered untouched gallery pages: %s' % markers
    return {'pdf_pages': count, 'stages': stages}

def gallery_add_to_limit_and_retake():
    start_mode('batch')
    import_images([1, 2, 3, 4, 5])
    actual, _ = process_review()
    assert actual == 5
    scroll_click(t('scan_screen_034'))
    assert count_camera() == 5
    import_images([6, 7, 8, 9, 10])
    wait(t('scan_screen_029'))
    assert count_review() == 10, 'Gallery append did not fill available five batch slots'
    scroll_click(t('scan_screen_046'))
    wait('Scanner Pro', partial=True)
    assert count_camera() == 9, 'A full batch did not free its retake slot'
    import_images([1])
    wait(t('scan_screen_029'))
    actual = count_review()
    pdf, count = save_pdf('gallery_limit_retake_saved')
    markers = pdf_page_markers(pdf)
    assert actual == 10 and count == 10 and markers == list(range(1, 11)), 'Full-batch add/retake count or order failed: review=%s PDF=%s markers=%s' % (actual, count, markers)
    return {'pdf_pages': count, 'page_order': markers, 'retake_at_capacity': True}

def gallery_reorder_delete_add():
    start_mode('batch')
    import_images([1, 2, 3])
    actual, _ = process_review()
    assert actual == 3
    click(t('scan_screen_041'))
    # LazyColumn preserves the moved item's viewport anchor. Return to the
    # first reviewed page before explicitly deleting it.
    for _ in range(3):
        d.swipe_ext('down', scale=.55)
        time.sleep(.2)
    click(t('scan_screen_042'))
    wait(t('scan_screen_050'))
    click(t('scan_screen_042'))
    assert count_review() == 2, 'Deleting a reviewed page did not update the batch'
    scroll_click(t('scan_screen_034'))
    assert count_camera() == 2
    import_images([4])
    assert count_camera() == 3
    actual, _ = process_review()
    pdf, count = save_pdf('gallery_reordered_saved')
    markers = pdf_page_markers(pdf)
    assert actual == 3 and count == 3 and markers == [1, 3, 4], 'Reviewed order/deletion was lost when adding a page: review=%s PDF=%s order=%s' % (actual, count, markers)
    return {'pdf_pages': count, 'page_order': markers}

def cached_gallery_dimensions():
    paths = adb('shell', 'run-as', PKG, 'find', 'cache/scan_review', '-type', 'f', '-name', '*.jpg').splitlines()
    sizes = []
    for i, path in enumerate(paths):
        raw = subprocess.check_output(['adb', 'exec-out', 'run-as', PKG, 'cat', path], timeout=30)
        local = OUT / (CASE + '_cache_%s.jpg' % i)
        local.write_bytes(raw)
        with Image.open(local) as image:
            sizes.append(list(image.size))
    return sizes

def bounds_without_changes():
    start_mode('batch')
    import_images([1])
    actual, _ = process_review()
    assert actual == 1
    before = cached_gallery_dimensions()
    assert len(before) == 1, before
    scroll_click(t('alpha20_bounds_title'))
    assert find(t('scan_screen_029')) is None
    click(t('alpha20_continue'))
    wait(t('alpha34_save_continue'))
    click(t('alpha34_save_continue'))
    wait(t('scan_screen_029'))
    after = cached_gallery_dimensions()
    assert len(after) == 1 and all(abs(a - b) <= 2 for a, b in zip(before[0], after[0])), 'Reopening bounds without changing corners discarded image edges: before=%s after=%s' % (before, after)
    pdf, count = save_pdf('unchanged_bounds_saved')
    assert count == 1 and pdf_page_markers(pdf) == [1]
    return {'before_dimensions': before, 'after_dimensions': after, 'pdf_pages': count}

def cancel_at_crop_commit():
    start_mode('batch')
    import_images([1])
    actual, _ = process_review()
    assert actual == 1
    scroll_click(t('alpha20_bounds_title'))
    assert find(t('scan_screen_029')) is None
    source = Path('project/app/src/main/java/com/abfilepro/app/core/ScanProcessor.kt').read_text()
    delete_line = next(i for i, line in enumerate(source.splitlines(), 1) if 'sourceFile.absolutePath != committed.absolutePath' in line)
    gate = CropCommitGate(PKG, delete_line)
    try:
        click(t('alpha20_continue'))
        gate.wait_hit()
        wait(t('p9_scanner_cancel_processing'))
        click(t('p9_scanner_cancel_processing'))
        snapshot('cancelled_at_crop_commit')
        gate.resume()
        time.sleep(1.5)
        wait(t('alpha20_bounds_title'))
        assert find(t('alpha34_save_continue')) is None, 'Cancelled processing navigated into editor after cancellation'
    finally:
        gate.close()
    click(t('alpha20_continue'))
    try:
        wait(t('alpha34_save_continue'), timeout=15)
    except TimeoutError:
        raise AssertionError('Cancelling bounds processing lost the committed page image')
    click(t('alpha34_save_continue'))
    wait(t('scan_screen_029'))
    assert count_review() == 1
    pdf, count = save_pdf('cancelled_crop_recovered_saved')
    assert count == 1 and len(PdfReader(pdf).pages[0].images) == 1
    return {'cancel_at_destructive_commit': True, 'page_recovered': True, 'pdf_pages': count}

cases = {
    'capture3_save_restart': capture_save_three,
    'gallery_import3_save': import_three,
    'gallery_import10_save': import_ten,
    'add_after_review5': add_after_five,
    'batch_retake_replace': retake_replaces_page,
    'batch_bounds_button': edit_bounds_batch,
    'card_missing_back_guard': card_one_side,
    'card_two_sides_save': card_two_sides,
    'document_save_pdf': document_save_pdf,
    'gallery_bounds_retake_keep_pages': gallery_retake_preserves_siblings,
    'gallery_add_limit10_retake': gallery_add_to_limit_and_retake,
    'gallery_reorder_delete_add': gallery_reorder_delete_add,
    'gallery_bounds_no_change': bounds_without_changes,
    'batch_crop_cancel_keeps_page': cancel_at_crop_commit,
}
selected = os.environ.get('SCANNER_TEST_CASES', ','.join(cases)).split(',')
for name in selected:
    run_case(name, cases[name])
adb('logcat', '-d', '-v', 'threadtime', check=False)
(OUT / 'logcat.txt').write_text(adb('logcat', '-d', '-v', 'threadtime', check=False), encoding='utf-8')
print('P10_SUMMARY ' + json.dumps({'PASS': sum(x['status']=='PASS' for x in RESULTS), 'FAIL': sum(x['status']=='FAIL' for x in RESULTS), 'BLOCKED': sum(x['status']=='BLOCKED' for x in RESULTS)}, ensure_ascii=False), flush=True)
sys.exit(1 if any(x['status'] != 'PASS' for x in RESULTS) else 0)
