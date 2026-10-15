# Storage & Real File Manager Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a safe persistent SAF file-manager mode beside the existing app-private manager and close the source-level gaps in point 1.

**Architecture:** Preserve all existing private-workspace behavior. Add a dedicated SAF storage manager and Compose pane, sharing only pure deterministic rules with the old manager.

**Tech Stack:** Kotlin, Jetpack Compose, Android DocumentsContract/SAF, coroutines, java.util.zip, SHA-256.

**Spec:** `docs/superpowers/specs/2026-09-14-storage-real-file-manager-design.md`

## Global Constraints
- Keep package `com.abfilepro.app`.
- Patch only; do not remove working file-manager behavior.
- No MANAGE_EXTERNAL_STORAGE.
- Persist URI permissions using SAF.
- Clean partial outputs on cancellation/failure.
- Do not claim 10/10 without Android build + device evidence.

---

### Task 1: Pure file-manager rules
**Files:**
- Create: `app/src/main/java/com/abfilepro/app/core/FileManagerRules.kt`
- Create: `app/src/test/java/com/abfilepro/app/core/FileManagerRulesTest.kt`

- [ ] Write tests for safe names, unique duplicate names, sorting and ZIP traversal rejection.
- [ ] Run the pure Kotlin harness and confirm failure because production rules do not exist.
- [ ] Implement minimum rules.
- [ ] Re-run pure tests and confirm pass.

### Task 2: SAF storage engine
**Files:**
- Create: `app/src/main/java/com/abfilepro/app/core/SafStorageManager.kt`

- [ ] Persist and validate tree permissions.
- [ ] Implement document query/list/create/rename/delete.
- [ ] Implement cancellation-safe recursive copy/move.
- [ ] Implement ZIP create/extract with traversal protection.
- [ ] Implement tree scan, largest files and SHA-256 duplicate groups.
- [ ] Implement favorites/recent persistence using URI identities.

### Task 3: SAF Compose manager
**Files:**
- Create: `app/src/main/java/com/abfilepro/app/ui/screens/SafFileManagerPane.kt`

- [ ] Add folder picker/change-folder state.
- [ ] Add list/grid, sort/search, favorites/recent and navigation.
- [ ] Add create/rename/delete/share/open.
- [ ] Add multi-select copy/move/delete and destination chooser.
- [ ] Add ZIP create/extract and analyzer dialogs.
- [ ] Handle revoked permissions and provider failures without crash.

### Task 4: Integrate safely with existing Files screen
**Files:**
- Modify: `app/src/main/java/com/abfilepro/app/ui/screens/FilesScreen.kt`
- Create: `app/src/main/res/values/strings_v200_storage.xml`

- [ ] Add a device-storage entry point without changing existing operations.
- [ ] Route back from SAF mode to the existing workspace manager.
- [ ] Add Arabic/English/French strings for all new visible copy.

### Task 5: Audit and release evidence
**Files:**
- Modify: `AB_FILE_PRO_V2_MASTER_14_POINT_TRACKER.md`
- Create: `PATCH_REPORT_V2.0.0_POINT1_STORAGE.md`
- Modify: `VERSION`

- [ ] Validate XML/resources and pure Kotlin rules.
- [ ] Run source-level static checks for broad storage permission and unsafe path assumptions.
- [ ] Attempt Android build if a usable SDK/Gradle environment is available.
- [ ] Record exact blockers and do not mark VERIFIED 10/10 without device validation.
