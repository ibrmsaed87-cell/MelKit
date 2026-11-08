# Validation for the accumulated 0.1.11 fixes

Prepared on 8 November 2026 after the user's phone test round. The user approved
implementing the accumulated changes while deferring three two-phone cases.
**Do not merge to main, synchronize to GitLab, or start an APK build until the user
approves that build.** The normal main synchronization would start GitLab CI.

## Problem and implementation

- Bundle creation, import, old success messages, navigation, and file selection
  occupied one long page. The tool now opens at a two-card entry screen. Creation,
  saved-bundle actions, and imported contents have separate pages. A persistent
  bottom action saves the selection; outlined secondary actions and compact remove
  icons make controls distinct from names and status text.
- Leaving the section clears session-only state. An in-progress file operation
  completes before its view-model state is cleared. A foreground transfer is not
  canceled by navigation. No published copy or received batch is deleted by reset.
- Received/extracted file saves now produce a bottom snackbar with the final path
  and an Open folder action. The same final path is retained on the result card.
  The folder action first tries a directory VIEW intent, then a system document
  browser with an initial folder on API 26+. Device-specific folder navigation
  remains to be verified on the phone; it requests no broad storage permission.
- DownloadCopy reads RELATIVE_PATH and DISPLAY_NAME after publishing a pending
  MediaStore row. Publication may select a unique numbered filename. The returned
  final path is used by both the operation journal and save confirmation. Legacy
  storage continues using its exclusively reserved filename. Old journal entries
  are retained as recorded; their unknown numbered paths are not guessed.
- Starting a new action clears earlier feedback. Import clears the previous open
  batch and cover before validating a replacement. Failed ZIP verification no
  longer coexists with the preceding verification success.
- Shizuku connection failures are rendered in one app-level dialog on every route,
  with Open Shizuku, Setup guide, and dismiss actions. The user confirmed that
  connection works when the service is ready; this fixes missing failure feedback,
  not the connection or pairing protocol.
- App-link sharing targets the Google Play URL for the installed application ID.
  No claim is made that the listing is already publicly available.
- Restore-section filter chips wrap to additional lines. The prior branch change
  describing the latest group switch and its timestamp is included in this batch.

## Batch restore added to the same deferred build

The user separately approved implementing this feature without a new APK build.
It is available only for the contents of one opened, verified MelKit bundle, not
for arbitrary archives or a mixture of received batches.

- Bundle-member checkboxes and Select all capture a subset. One confirmation shows
  the count and existing-file policy before invoking the existing single-file
  `GameLibraryViewModel.restoreSaved` API sequentially. There is no new AIDL method
  or replacement-write path. Each confirmed success retains its normal journal entry.
- `BatchRestore` validates the complete plan before any write, bounds it to the
  bundle limit, and rejects duplicate destination keys. It allows one in-flight
  file and counts success only after an `ok` response. `exists` means skipped.
- Ordinary per-file failures continue. Connection loss, another busy operation,
  pending mod recovery, partial cleanup, or an unconfirmed reply stop further
  attempts. Remaining files are explicitly Not attempted, not failed or restored.
- Stop requests and leaving the section stop between files. They do not interrupt
  the current verified write or remove earlier completed files. An interrupted
  reply is reported as unconfirmed; retry still uses the no-overwrite policy.
- Counts remain visible at the bottom while the batch is active. The finished
  result scrolls into view and produces a snackbar summary; each selected file has
  its own outcome. Failed and unattempted members remain selected for retry.
- Results are session state, retained across rotation but cleared when the bundle
  section is left after work finishes. The existing journal retains confirmed
  per-file successes; the whole batch is not an atomic transaction.

## Evidence and limits

- `ci/check-content-paths.sh`: 317 assertions passed across paths (24), exports
  (26), previews (29), restore (22), vault (33), profiles (61), scan (32), wire
  transfer (31), bundles (20), and batch restore (39). Batch checks include immutable
  selection/results, bounds, duplicate/unsafe paths, one in-flight file, mixed
  outcomes, recoverable connection stops, stopping before and during a write,
  and retries. Integration checks use the production NewFileRestore with real
  temporary files: existing bytes are preserved, a bad checksum publishes no
  file, a later save succeeds, and stopping during a write leaves a complete
  verified file and does not start the next. These checks do not test Android UI.
- XML parsing, duplicate string detection, AR/EN placeholder matching, and resource
  reference resolution passed: 368 translated strings and 367 referenced keys.
- Exact source syntax parsing passed for DirectShareScreen, MelKitApp,
  OperationHistoryScreen, LibraryScreen, SavedFileLocation, DownloadCopy, and the
  app Gradle script. This is a grammar check, not Kotlin type checking or Compose
  compiler validation. The host has no Android SDK or Kotlin/Gradle executable.
- Android compilation, resource linking, directory-intent handling, and new-screen
  AR/EN layout are **not yet tested**. No APK or AAB was built for this batch.

## Focused phone checks after an approved build

1. Install over the existing app using the original signing key. Open Bundles,
   create a mixed bundle, share it, then open a bundle and restore a file.
2. Save the same extracted file twice. Confirm both numbered filenames and actual
   paths in Downloads, the snackbar, and the journal. Try Open folder.
3. Select an ordinary ZIP after a valid bundle. Confirm a clear error and no old
   success or old open contents. Leave the section and return: entry cards should
   appear with no old selection or cover.
4. With Shizuku stopped, press Connect from Restore and undo and another screen.
   Check the visible dialog and Open Shizuku action; retry after starting service.
5. Review AR/EN and large text: persistent create button, selection paths, restore
   filters, latest-switch details, and the new Google Play share text.

### Batch phone checks, deferred by the user

- Open a mixed bundle. Select a subset, then select/deselect all. Confirm only the
  captured selected members appear in the batch result.
- Restore into empty and occupied destinations. Confirm exact completed files,
  existing-file skips, per-file outcomes, counters, and journal entries.
- Stop mid-batch and disconnect Shizuku between files. Confirm completed files
  remain, pending files are Not attempted, and retry never overwrites a game file.
- Rotate while running, leave/return, and inspect AR/EN at large font sizes.

## Deferred cases requiring the second phone

- Native QR transfer of a complete ZIP with its cover, then opening and restoring.
- Transfer cancellation before completion, retaining completed batches only.
- A subsequent session with a fresh QR and no stale file/progress state.

The user's confirmed ordinary-file QR transfer and successful in-game opening are
recorded separately in README. They do not stand in for these three cases.

## Primary references checked

- MediaProvider publication/movement behavior:
  https://android.googlesource.com/platform/packages/providers/MediaProvider/+/refs/heads/main/src/com/android/providers/media/MediaProvider.java
- DocumentsContract initial location and its API-level limit:
  https://developer.android.com/reference/android/provider/DocumentsContract#EXTRA_INITIAL_URI
- FlowRow wrapping behavior:
  https://developer.android.com/develop/ui/compose/layouts/flow
