# Phone review changes — 9 November 2026

The user authorized four code changes together, explicitly without a new build.
Base: GitHub `main` at `0d8e2ba959163abd2d43c91b18fecd0ce939db4d`.
Work branch: `codex/melkit-ui-review-20261109`. Do not synchronize `main` or start
GitLab/Android builds for this change until requested.

## Behavior

1. Home counts use a bounded, read-only recursive metadata walk of Mods, Maps and
   Saves through the scoped Shizuku service. They count matching file extensions,
   including nested folders and files beyond the library's 40-item page. Disabled
   mods and Worlds progress are outside these totals. Missing content folders are
   zero only after the game root was successfully listed. Listing failures,
   aliases, non-regular entries and depth/entry limits produce an unavailable
   state, never a false zero or partial total. The UI refreshes on home entry,
   operation completion while home is shown, and lifecycle resume. Disconnection
   clears totals. Metadata enumeration does not read file bodies or classify
   corruption.
2. The official Shizuku download button opens the exact supplied Play URL.
3. Library and saved-copy cards share a 64dp preview and a single-line ellipsized
   filename. The full name and path are selectable in details. File information
   is grouped; primary actions have at least 48dp height. Library read-test,
   organization and cover actions are in the overflow menu. Arabic/English text
   direction is retained, including filenames containing Latin characters.
4. A saved-copy name conflict offers a separate confirmation to restore a new
   numbered copy. Its bytes and SHA-256 are verified in staging, then its name is
   reserved atomically with `createNewFile()`. A late collision advances to the
   next number without reusing the consumed source stream. The result and journal
   use the actual new relative path. Existing files and Downloads copies remain.
   The normal restore endpoint is unchanged in overwrite behavior, including for
   batch restoration, mod-vault returns and received files. The new AIDL methods
   use transaction IDs 11 and 12; IDs 1–10 remain unchanged.

## Verification

- `ci/check-content-paths.sh`: **369 checks passed**. The existing 317 passed,
  plus 17 count checks and 35 numbered-restore checks.
- Count cases: recursive Arabic paths, more than 40 files, case-insensitive
  extensions, empty folders, wrong-type files, deleted files, excluded outside
  content, escaping links, cycles, missing/non-directory roots and depth limit.
- Restore cases: all four supported types, original and earlier-copy bytes
  unchanged, successive suffixes, nested Arabic names, late collisions, wrong
  hash/length, rejected traversal/escaping links and staging cleanup.
- XML parsed; **380 translated string keys** match between English and Arabic,
  including format placeholders. All 376 Kotlin string references resolve.
- Tree-sitter syntax review of the seven changed/new Kotlin source files.
  This is not Android compilation or a Compose layout test.

The host runtime exposes the Java compiler module without a `javac` launcher;
the host checks ran through `java --module jdk.compiler/com.sun.tools.javac.Main`.
No Gradle, APK/AAB build, emulator install, signing operation, CI configuration
change or main-branch push was performed for this task.

## Phone evidence and remaining review

Before these four changes, the user confirmed that the tested map, mod and save
were restored and used in Melon Sandbox, reconnection worked after starting
Shizuku, and the two guide videos played in the corresponding app languages.
The installed version was not identified. These confirmations do not validate
this new code. After an authorized build, review home counts and retry/offline
states, compact cards at Arabic/English and large font sizes, the exact Play
destination, and a conflicting restore followed by opening both game files.
