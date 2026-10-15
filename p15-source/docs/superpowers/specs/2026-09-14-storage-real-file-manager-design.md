# AB File Pro V2 — Storage & Real File Manager Design

## Scope
Point 1 only. Preserve the existing app-private file manager and add a second, user-owned storage mode based on Android Storage Access Framework (SAF). No broad storage permission and no MANAGE_EXTERNAL_STORAGE.

## Architecture
- Existing `FilesScreen` remains the app-workspace manager.
- New `SafFileManagerPane` handles a user-selected document tree and is entered from the existing Files screen.
- New `SafStorageManager` owns persistent tree permissions, browsing, file operations, ZIP, analyzer, favorites/recent persistence and duplicate detection.
- New pure `FileManagerRules` owns deterministic rules that can be JVM-tested without Android SDK: safe names, duplicate-name resolution, sort rules, ZIP path safety and extension/type helpers.

## SAF behavior
- Folder selection uses `ACTION_OPEN_DOCUMENT_TREE` and `takePersistableUriPermission` with read/write flags.
- The selected tree URI is persisted in SharedPreferences and revalidated before use.
- If permission is revoked or the provider becomes unavailable, the UI returns to a recoverable "choose folder again" state instead of crashing.
- Browsing uses `DocumentsContract` only and never assumes a filesystem path exists for a URI.

## File operations
- New folder, rename, delete, copy and move use provider APIs where supported.
- Stream-copy fallback is cancellation-aware and deletes partial outputs on failure.
- Multi-select supports files and folders.
- Duplicate target names use deterministic `name (2).ext`, `name (3).ext` resolution.
- External SAF delete is explicitly permanent because Android document providers do not expose a portable recycle-bin contract. The existing app-workspace Trash remains recoverable.

## ZIP
- Create ZIP from selected SAF files/folders with streaming I/O and cancellation.
- Extract ZIP to a uniquely named folder.
- Reject absolute/traversal ZIP paths and never write outside the created destination folder.

## Analyzer
- Scan the selected tree without leaving it.
- Report file/folder counts and total declared size.
- Show largest files.
- Duplicate finder uses SHA-256 for candidate files and does not delete duplicates automatically.

## UI
- Add a visible "Device storage" entry from the existing manager.
- SAF mode supports list/grid display, search in current folder, sort name/date/size/type, favorites, recent files, multi-select, copy/move/delete, ZIP, extract and storage analyzer.
- A "Change folder" action can replace the persisted SAF tree deliberately.

## Safety constraints
- Package stays `com.abfilepro.app`.
- No existing working feature is removed.
- No automatic migration or deletion of app-private files.
- No signing-key changes.
- No broad storage permission.
- Point 1 cannot be declared VERIFIED 10/10 until Android build and real-device permission-loss/low-storage/large-file tests are run.
