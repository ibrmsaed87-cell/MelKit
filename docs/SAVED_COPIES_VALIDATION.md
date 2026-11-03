# Saved copies with thumbnails and new-file restore — 0.1.5

## Requested behavior and phone evidence

The user confirmed visible mod thumbnails. Screenshots show a verified map export under
`Download/MelKit/Maps` and repeated exports retaining the first file with numbered names.
The displayed screenshots do not establish an installed version code, all thumbnail types,
or restore acceptance in the game.

The user requested thumbnail + name for saved files, and hiding SHA in the next save dialog
update. The agreed next development step was restoring saved copies to the game.

## Saved-copy cards

- Replace the planned Backups screen with real saved-copy cards: 64dp rounded thumbnail,
  filename, size, saved path, and Restore to game for supported files. Aspect ratio and
  nearest-neighbor rendering preserve the embedded pixel icons. Missing images keep a type
  placeholder; no file is described as corrupted merely because a preview is unavailable.
- Android 10+: query accessible, published `MediaStore.Downloads` entries below the exact
  `Download/MelKit/` prefix. This reads the copies the app owns without broad storage or
  media-library permissions. Copies elsewhere or inaccessible after reinstallation need
  a later SAF adoption flow; this is not a general downloaded-mod importer.
- Android 7–9: request read-storage permission, restricted to API 28 in the manifest, then
  enumerate the MelKit folder while bounding canonical paths and directory cycles.
- Decode embedded preview streams on IO workers with serialized requests and a 4 MiB LRU.
  ZIP streams have compressed/decompressed budgets and no extraction to filesystem paths.
  Saved cards read their own files without requiring Shizuku.
- The export-success dialog hides the SHA label and value. The save pipeline still compares
  hashes and sizes; the separate developer read test retains the fingerprint display.

## Restoration prototype

- The Restore action asks the user to close the game, shows the chosen filename/path, and
  requires an authorized Shizuku connection. It reads only from the selected saved copy.
- AIDL adds one scoped `restoreFile` endpoint. Allowed pairs are Maps/`.melmap`, Mods/`.melmod`,
  Saves/`.melsave` and Worlds/`.melworld`; nested paths still pass the existing canonical
  game-content policy. Arbitrary JSON/progress metadata cannot be restored individually.
- The app hashes a descriptor before transferring a fresh read descriptor. The service
  streams into an operation-owned temporary file in the target directory, checks size/hash,
  syncs and reopens it, then reserves the destination with exclusive `createNewFile()`.
- Existing destinations are rejected both before staging and at exclusive reservation.
  The verified staging file replaces only that operation-owned empty reservation, and the
  final file is reopened and verified again. No source backup or pre-existing game file is
  deleted or overwritten. Ordinary failures clean only operation-owned files; failure to
  clean produces a separate partial-copy error.
- The actual saved filename is used, including `(1)` where present. Do not infer or strip
  suffixes, rewrite internal world IDs, or claim that duplicate world IDs are accepted by
  the game. A matching player-progress association is not implemented.
- A killed process may leave a staging file, or an empty reservation in the narrow interval
  before rename. Durable interruption recovery still needs work; it is not claimed complete.
  Restoring to a new filename keeps existing game originals outside that operation.

## Verification and limits

- JVM 17: 24 content-path checks, 26 export-path checks, 35 preview checks and 24 restore
  checks passed locally, for 109 total. Eight use private map/world samples; those samples
  remain outside git. CI runs the remaining 101 cases without private inputs.
- Preview tests include stream-based extraction of the supplied map and world, matching
  their independently extracted PNGs. Restore tests exercise exact byte preservation,
  existing files, a file created during staging, wrong hashes/sizes, interrupted reads,
  cross-folder/traversal rejection, symlink escape and cleanup. The real map is restored
  into a host temporary directory only; this is not an Android/game restore test.
- XML, 134 translated strings, 137 string references and Bash syntax pass locally.
- Android builds and signing remain exclusively in GitLab with the existing key. No local
  APK build, emulator behavior, MediaStore query success, Binder restore, FUSE rename result,
  or in-game acceptance is claimed. Those require the signed APK and phone testing.

First phone check: open Backups and verify thumbnails beside the five saved map filenames.
Connect Shizuku and restore a numbered map whose exact filename is absent from the game.
Check the success dialog and then its presence/opening in Melon Sandbox. Retry that same
restore to confirm a collision leaves the existing game file unchanged. Confirm the saved
copy still exists, and saving a new copy no longer exposes SHA. Continue with other file
types and player-progress testing only after observing the first map result.

Reference: [Android shared Downloads and ownership](https://developer.android.com/training/data-storage/shared/media).
