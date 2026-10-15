# AB File Pro Alpha11 Smart Scan Design

## Goal
Replace the current scanner landing page with a direct-task smart scan hub while preserving the working scanner/review pipeline and the existing file manager.

## Protected Files
- Do not modify `MainActivity.kt`.
- Do not modify `FileUtils.kt`.
- Do not modify `FilesScreen.kt`.

## Direct Actions
The scan hub exposes seven direct actions. Tapping a card starts that function immediately; there is no separate start button.

1. `مستند فردي`: dedicated document camera, one page, edge detection, then image review/edit, then save/share.
2. `مستند متعدد الصفحات`: dedicated document camera, up to 10 pages captured consecutively, then reorder/delete/edit, then save/share as one PDF.
3. `بطاقة وجهين`: dedicated card flow, front then back, review/edit both sides, then save as one combined PDF page and share.
4. `إيصال`: dedicated receipt camera, one receipt, document edge detection and text-friendly enhancement, then review/edit, then save/share.
5. `QR / باركود`: open live barcode camera directly. No still capture or image edit. Show detected value with copy/share and URL open action where applicable.
6. `الترجمة`: dedicated document camera, one image, review/edit, OCR, automatic language identification, translate to a selected target language, then save translated text to the AB File Pro workspace and share.
7. `صور إلى PDF`: camera/gallery flow for up to 10 images. User can capture/import sequentially, reorder/delete/rotate/crop/enhance, then save one PDF and share.

## Storage
All saved outputs must use the existing `FileUtils.outputDir(...)` behavior so they are inside the AB File Pro file-manager workspace and respect the user's default save folder. No FileUtils change is needed.

## Review/Edit
Reuse `ScanReviewContent` for still-image modes. Keep crop, perspective, rotation, filters, page reorder/delete, retake, and PDF quality controls.

## UI
Replace the current icon-only scanner cards and settings-heavy top area with image-backed direct-action cards. No floating help robot on the hub. The seven task cards are the focus. Existing scanner camera and edit pages retain their stable UI except for mode-specific behavior and page limits.

## Translation
Use OCR already present in the app and add ML Kit language identification plus on-device translation. Translation models may download on first use. Supported target choices in Alpha11: Arabic, English, French.

## Version
- Package: `com.abfilepro.app`
- Version: `2.0.0-alpha11`
- Version Code: `61`
- Original signing identity must be preserved.

## Acceptance
- Alpha10 protected files remain byte-identical.
- Seven cards launch the correct flow directly.
- Batch and images-to-PDF are limited to 10.
- Barcode opens live reader directly.
- Still-capture modes always enter review/edit before save.
- Saved outputs are inside the AB File Pro workspace and can be shared.
