"""Real Scanner UI regressions for isolated P13/P14 trial APKs.

Run from the CI checkout root (which contains project/).  The original P11
helpers and fourteen cases remain unchanged; this runner loads their setup and
definitions without their automatic case dispatch.  No app instrumentation or
fake processor is installed.

SCANNER_TEST_SUITE=new (default), legacy, or all chooses a group.  An explicit
SCANNER_TEST_CASES comma-separated list takes precedence.
"""

import io
import os
import sys
from pathlib import Path


ALLOWED_PACKAGES = {
    "com.abfilepro.app.p13sharetrial",
    "com.abfilepro.app.p14mainttrial",
}
package = os.environ.get("SCANNER_TEST_PACKAGE", "com.abfilepro.app.p14mainttrial")
if package not in ALLOWED_PACKAGES:
    raise RuntimeError("Scanner checks only allow separate P13/P14 disposable trial packages")
os.environ["SCANNER_TEST_PACKAGE"] = package

helper_path = Path("p14-ci/retained_scanner_runtime.py")
if not helper_path.is_file():
    raise RuntimeError("Run Scanner checks from the checkout root containing project/")
sys.path.insert(0, str(Path("tools").resolve()))
helper_source = helper_path.read_text(encoding="utf-8")
guard = "if PKG != 'com.abfilepro.app.p11scannertrial':"
if helper_source.count(guard) != 1:
    raise RuntimeError("The retained P11 helper package guard changed; inspect before running")
helper_source = helper_source.replace(guard, "if PKG not in ALLOWED_PACKAGES:", 1)
helper_prefix, dispatch, _ = helper_source.partition("\nselected = os.environ.get(")
if not dispatch:
    raise RuntimeError("The retained P11 helper dispatch changed; inspect before running")
# The helper still enforces ro.kernel.qemu == 1 before any destructive reset.
exec(compile(helper_prefix, str(helper_path), "exec"), globals())

from PIL import ImageChops, ImageStat
from pypdf.generic import ContentStream


def cache_images(tag):
    """Read real app-owned JPEGs without changing app state or file contents."""
    paths = adb(
        "shell", "run-as", PKG,
        "find cache/scan_review cache/scanner_pro -type f -name '*.jpg' 2>/dev/null",
        check=False,
    ).splitlines()
    records = {}
    for index, path in enumerate(sorted(paths)):
        raw = subprocess.check_output(
            ["adb", "exec-out", "run-as", PKG, "cat", path], timeout=30
        )
        local = OUT / (CASE + "_" + tag + "_cache_%02d.jpg" % index)
        local.write_bytes(raw)
        with Image.open(io.BytesIO(raw)) as original:
            image = original.convert("RGB")
            image.load()
        records[path] = {
            "sha256": hashlib.sha256(raw).hexdigest(),
            "size": list(image.size),
            "image": image,
        }
    return records


def one_cache_image(tag):
    records = cache_images(tag)
    assert len(records) == 1, "Expected one owned image, got %s" % list(records)
    return next(iter(records.values()))


def assert_same_frame(before, after, route):
    dimensions_ok = all(abs(a - b) <= 2 for a, b in zip(before["size"], after["size"]))
    assert dimensions_ok, (
        "%s discarded image edges: before=%s after=%s"
        % (route, before["size"], after["size"])
    )
    # Compare all four edge strips after fitting any harmless rounding pixel.
    # JPEG re-encoding may change intensities slightly, but a second perspective
    # crop changes the frame and its edge content substantially.
    first = before["image"].convert("L")
    second = after["image"].convert("L").resize(first.size)
    width, height = first.size
    strip = max(8, min(width, height) // 20)
    edge_boxes = [
        (0, 0, width, strip),
        (0, height - strip, width, height),
        (0, 0, strip, height),
        (width - strip, 0, width, height),
    ]
    deltas = [
        round(ImageStat.Stat(ImageChops.difference(first.crop(box), second.crop(box))).mean[0], 3)
        for box in edge_boxes
    ]
    assert max(deltas) <= 4.0, "%s changed edge pixels: mean absolute drift=%s" % (route, deltas)
    return {"before_dimensions": before["size"], "after_dimensions": after["size"], "edge_drift": deltas}


def wait_any(values, timeout=45):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for value in values:
            if find(value) is not None:
                return value
        time.sleep(.35)
    raise TimeoutError("Missing any of: " + repr(values) + "; actual=" + repr(texts()))


def batch_editor_back_same_frame():
    start_mode("batch")
    import_images([1])
    click("حفظ ومراجعة", partial=True)
    wait(t("alpha20_bounds_title"))
    click(t("alpha20_continue"))
    wait(t("alpha34_save_continue"))
    before = one_cache_image("flattened_before_editor_back")
    snapshot("first_flattened_editor")
    d.press("back")
    wait(t("alpha20_bounds_title"))
    snapshot("bounds_after_editor_back")
    click(t("alpha20_continue"))
    wait(t("alpha34_save_continue"))
    after = one_cache_image("flattened_after_editor_back")
    frame = assert_same_frame(before, after, "Batch editor Back -> unchanged bounds Continue")
    click(t("alpha34_save_continue"))
    wait(t("scan_screen_029"))
    assert count_review() == 1, "Editor Back lost the Batch review page"
    pdf, count = save_pdf("batch_editor_back_saved")
    assert count == 1 and pdf_page_markers(pdf) == [1]
    return {**frame, "pdf_pages": count, "page_markers": [1]}


def document_review_bounds_same_frame_twice():
    start_mode("document")
    import_images([1])
    screen = wait_any([t("scan_screen_029"), t("alpha20_bounds_title")])
    if screen == t("scan_screen_029"):
        scroll_click(t("alpha20_bounds_title"))
    wait(t("alpha20_bounds_title"))
    click(t("alpha20_continue"))
    wait(t("alpha34_save_continue"))
    before = one_cache_image("first_document_flatten")
    d.press("back")
    wait(t("scan_screen_029"))
    observations = []
    for repeat in range(2):
        scroll_click(t("alpha20_bounds_title"))
        wait(t("alpha20_bounds_title"))
        snapshot("document_bounds_repeat_%s" % (repeat + 1))
        click(t("alpha20_continue"))
        wait(t("alpha34_save_continue"))
        after = one_cache_image("document_repeat_%s" % (repeat + 1))
        observations.append(assert_same_frame(before, after, "Document Review -> unchanged bounds #%s" % (repeat + 1)))
        d.press("back")
        wait(t("scan_screen_029"))
        assert count_review() == 1, "Repeated Document bounds lost the review page"
    pdf, count = save_pdf("document_repeat_bounds_saved")
    assert count == 1 and pdf_page_markers(pdf) == [1]
    return {"unchanged_repeats": observations, "pdf_pages": count, "page_markers": [1]}


def click_card_retake(side_index):
    """Find a real Retake button under the requested visible page header."""
    for _ in range(3):
        d.swipe_ext("down", scale=.65)
        time.sleep(.2)
    expected_header = "الصفحة %s من 2" % (side_index + 1)
    retake_text = t("scan_screen_046").translate(DIGITS)
    for _ in range(28):
        root = ET.fromstring(dump())
        header = next((n for n in root.iter("node") if n.attrib.get("text", "").translate(DIGITS) == expected_header), None)
        if header is not None:
            header_y = center(header)[1]
            following_header_ys = [
                center(n)[1] for n in root.iter("node")
                if re.fullmatch(r"الصفحة\s+\d+\s+من\s+2", n.attrib.get("text", "").translate(DIGITS))
                and center(n)[1] > header_y
            ]
            next_header_y = min(following_header_ys, default=10**9)
            candidates = [
                n for n in root.iter("node")
                if n.attrib.get("text", "").translate(DIGITS) == retake_text
                and header_y < center(n)[1] < next_header_y
                and n.attrib.get("enabled") != "false"
            ]
            if candidates:
                snapshot("retake_side_%s_button" % (side_index + 1))
                d.click(*center(min(candidates, key=lambda n: center(n)[1])))
                time.sleep(.6)
                wait("Scanner Pro", partial=True)
                return
        d.swipe_ext("up", scale=.25)
        time.sleep(.4)
    raise TimeoutError("Retake button for " + expected_header + " unavailable: " + repr(texts()))


def card_front_retake_cancel_preserves_sources():
    start_mode("id")
    import_images([1, 2])
    wait(t("scan_screen_029"))
    assert count_review() == 2
    before = cache_images("card_before_cancel")
    assert len(before) == 2, "Card fixture import did not produce two source images"
    click_card_retake(0)
    entered = cache_images("card_while_retake_pending")
    click(t("scanner_pro_camera_004"))
    wait(t("scan_screen_029"))
    after = cache_images("card_after_cancel")
    actual = count_review()
    missing = [path for path in before if path not in after]
    changed = [path for path in before if path in after and before[path]["sha256"] != after[path]["sha256"]]
    assert actual == 2 and not missing and not changed and len(after) == 2, (
        "Cancelling front retake lost the approved card: review=%s before=%s pending=%s after=%s missing=%s changed=%s"
        % (actual, list(before), list(entered), list(after), missing, changed)
    )
    pdf, count = save_pdf("card_cancel_retake_saved")
    markers = card_pdf_image_markers(pdf)
    assert count == 1 and markers == [1, 2], "Cancelled card PDF order/images changed: %s" % markers
    return {"review_sides": actual, "source_hashes_unchanged": True, "pdf_pages": count, "image_order": markers}


def image_markers(image):
    image = image.convert("L")
    row = [image.getpixel((x, int(image.height * .62))) < 70 for x in range(image.width)]
    widths, width = [], 0
    for black in row + [False]:
        if black:
            width += 1
        elif width:
            widths.append(width)
            width = 0
    return sum(value >= 10 for value in widths)


def card_pdf_image_markers(pdf):
    reader = PdfReader(pdf)
    assert len(reader.pages) == 1, "Combined Card export must contain one PDF page"
    page = reader.pages[0]
    assert len(page.images) == 2, "Combined Card PDF must embed both source images"
    # Inspect draw order, which is the actual front/back placement order, rather
    # than depending on the PDF resource dictionary's key iteration order.
    image_order = []
    resources = page["/Resources"]["/XObject"]
    for operands, operator in ContentStream(page.get_contents(), reader).operations:
        if operator != b"Do":
            continue
        name = operands[0]
        if resources[name].get_object().get("/Subtype") == "/Image":
            image_order.append(image_markers(page.images[str(name)].image))
    assert len(image_order) == 2, "Combined Card PDF did not draw both images: %s" % image_order
    return image_order


def card_retake_preserves_order(side_index):
    start_mode("id")
    import_images([1, 2])
    wait(t("scan_screen_029"))
    assert count_review() == 2
    before = cache_images("card_before_replacement")
    assert len(before) == 2
    click_card_retake(side_index)
    import_images([3])
    wait(t("scan_screen_029"))
    actual = count_review()
    assert actual == 2, "Replacement changed Card page count: %s" % actual
    after = cache_images("card_after_replacement")
    unchanged = [path for path in before if path in after and before[path]["sha256"] == after[path]["sha256"]]
    assert len(after) == 2 and len(unchanged) == 1, "Replacement did not preserve exactly one untouched source: %s" % unchanged
    pdf, count = save_pdf("card_retake_%s_saved" % ("front" if side_index == 0 else "back"))
    markers = card_pdf_image_markers(pdf)
    expected = [3, 2] if side_index == 0 else [1, 3]
    assert count == 1 and markers == expected, (
        "Card %s retake appended/reordered saved sides: expected=%s actual=%s"
        % ("front" if side_index == 0 else "back", expected, markers)
    )
    return {"review_sides": actual, "untouched_source_preserved": True, "pdf_pages": count, "image_order": markers}


legacy_cases = dict(cases)
new_cases = {
    "batch_editor_back_no_recrop": batch_editor_back_same_frame,
    "document_review_bounds_no_recrop_twice": document_review_bounds_same_frame_twice,
    "card_front_retake_cancel_preserves_sources": card_front_retake_cancel_preserves_sources,
    "card_front_retake_preserves_pdf_order": lambda: card_retake_preserves_order(0),
    "card_back_retake_preserves_pdf_order": lambda: card_retake_preserves_order(1),
}
cases.update(new_cases)
suite = os.environ.get("SCANNER_TEST_SUITE", "new")
groups = {"new": new_cases, "legacy": legacy_cases, "all": cases}
if suite not in groups:
    raise RuntimeError("SCANNER_TEST_SUITE must be new, legacy, or all")
selected = os.environ.get("SCANNER_TEST_CASES", ",".join(groups[suite])).split(",")
unknown = [name for name in selected if name not in cases]
if unknown:
    raise RuntimeError("Unknown Scanner cases: " + repr(unknown))
for name in selected:
    run_case(name, cases[name])
(OUT / "logcat.txt").write_text(adb("logcat", "-d", "-v", "threadtime", check=False), encoding="utf-8")
summary = {
    "package": PKG,
    "suite": suite,
    "PASS": sum(result["status"] == "PASS" for result in RESULTS),
    "FAIL": sum(result["status"] == "FAIL" for result in RESULTS),
    "BLOCKED": sum(result["status"] == "BLOCKED" for result in RESULTS),
}
(OUT / "scanner_summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
print("P14_SCANNER_SUMMARY " + json.dumps(summary, ensure_ascii=False), flush=True)
sys.exit(1 if any(result["status"] != "PASS" for result in RESULTS) else 0)
