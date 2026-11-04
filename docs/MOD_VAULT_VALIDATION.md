# Mod vault — 0.1.6

## Problem and scope

On 4 November 2026 the user confirmed that the Tools entries in the installed app
still opened preparation screens. The next implemented tool is the individual
`.melmod` toggler; scanner, profiles, bundles, categories and custom thumbnails
remain planned. Tools cards now show that status before opening them.

This batch also changes the connected Shizuku card from setup instructions to a
ready-to-browse message, as requested. The user prefers batching changes into one
APK build and one phone test round.

## Behavior

- Active nested `.melmod` files and disabled copies are listed with names, sizes,
  previews and state. The list is paginated; access errors are not corruption.
- The user must confirm closing the game before each modification.
- The shared vault is `Download/MelKit/DisabledMods/<operation-id>`; it is outside
  both game and app-specific storage and is not removed when MelKit is removed.
- Each entry records the original relative mod path, length, SHA-256 and operation
  state. Journal replacement is atomic and its file descriptor is synced.
- Disable writes and syncs a separate copy, verifies its length and SHA-256,
  publishes it, records `prepared`, rechecks the source, and only then removes the
  source. A discoverable journal/copy remains if the process stops during removal.
- Enable verifies the vault copy and uses the existing no-overwrite restore.
  A different existing game file is retained and reported as a conflict. An
  identical existing file allows idempotent completion of an interrupted enable.
- Safety copies remain after enabling. Repeated toggles of identical content reuse
  the same enabled record rather than adding another copy. Storage headroom is
  checked before copying. Unreadable or incomplete records are retained and warned
  about; valid other records remain available.
- Both service and UI restrict the operation to mod files. No mod IDs, names or
  content bytes are rewritten; the original nested path is restored.

## Host evidence

`bash ci/check-content-paths.sh` passed on the host using OpenJDK 17's compiler
module (the host has no `javac` launcher on PATH). Output:

```text
Content path checks passed: 24
Export path checks passed: 26
Preview image checks passed: 29
New-file restore checks passed: 22
Mod vault checks: 33 passed
```

The new cases cover exact byte round-trip, nested Arabic names, unchanged copy
reuse, process restart, interrupted transactions before and after source removal,
idempotent enable, stale list metadata, low-space disable and enable, differing
target collision, changed/missing vault payloads, invalid journal handling,
traversal, symlinks and untouched unrelated files. Interruption is simulated by
persisting the corresponding journal state and recreating the manager; this is
not a test of an Android process being killed.

XML parsing, 154 matching translatable Arabic/English keys and 157 referenced
string keys were checked. Non-translatable app/product names use the existing
default resources. The permanent signing configuration was not changed.

## Pending validation

No Android SDK, emulator or Kotlin compiler is present on this host. Host Java
checks do not compile Compose, Kotlin, AIDL or resources into an Android app.
The single GitLab build must establish that compilation; its result is not yet
claimed. Android 16 Shizuku writes to the shared vault and the following phone
steps also remain unverified:

1. Install the update over the existing app using the original signing key.
2. Close the game; disable one known mod. Confirm its vault card and that the mod
   is absent after reopening the game.
3. Close the game; return the mod. Confirm it works after reopening the game.
4. Reopen MelKit and reconnect Shizuku to confirm persisted state and previews.
5. Verify conflict and interrupted-connection handling with disposable test mods.
6. Review Arabic/English layouts, the connected Shizuku text and preparation labels.

The copy protocol requires the game to be closed and is not a file-system lock
against another app editing the source concurrently. Physical power-loss
durability of Android shared-storage directory entries has not been established.
The vault journal is not a completed general operation-history UI. Multi-file mod
folders, activation profiles and world-progress linking remain outside this batch.
