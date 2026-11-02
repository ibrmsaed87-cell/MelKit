# Embedded library previews — 0.1.3

## Request and observed files

The user requested a thumbnail beside each mod/map/save/world card. Inspect the actual
format first; keep optional preview reading separate from file-validity checks.

| Supplied sample | Observed representation | PNG bytes | Image size |
| --- | --- | --- | --- |
| `Controllable_melon.melmap` | ZIP member `MapPreview`; `MetaData.PreviewImage.AssetId` points to it | 29,428 | 445×317 |
| `Blue Android.melmod` | Root JSON `icon` array of byte values | 262 | 12×11 |
| `save.melsave` | Root JSON `icon` array of byte values | 1,851 | 64×64 |

All three extracted images were opened and verified as PNGs locally. The map sample's
SHA-256 matches the phone's earlier successful read/export proof:
`cf0a67f087ad33f04441cbf0767ea9536adf54e806ef9189c860d29303811e8a`.
No user samples or extracted assets are committed or bundled in the application.
No distinct Worlds content format has been supplied or verified.

## Implementation

- A bounded, read-only `readPreview` endpoint resolves the existing game-content path
  policy and accepts regular files only. Existing export/read endpoints are unchanged.
- `.melmap`: read only the exact `MapPreview` member through `ZipFile`, without expanding
  textures or extracting archive paths. Verify the member size and CRC before decoding.
- `.melmod`/`.melsave`: use Android `JsonReader` to find the root `icon` array. Stream and
  skip unrelated fields instead of loading model textures or save objects into memory.
- Return at most 256 KiB per preview through Binder; accept PNG dimensions of 1–4096
  on each side. JSON scanning is bounded to 16 MiB; ZIP previews are optional for archives
  up to 64 MiB and at most 4096 entries. Files outside those preview limits still retain
  their type icon and normal read/export actions.
- The app checks source metadata before and after the read. It decodes a sampled bitmap
  on an IO dispatcher, serializes thumbnail loads, and keeps at most 4 MiB in an LRU cache.
- Visible cards request their image through a cancellable Compose producer. A folder
  refresh increments the preview revision, including retries for previously missing images.
- File cards reserve 64dp beside the name, clip rounded corners, preserve aspect ratio,
  and use nearest-neighbor rendering to retain small pixel icons. No extra translated text
  is introduced, and the existing RTL/LTR row direction still determines placement.
- Missing previews, read errors, unsupported formats, decode failures and oversized images
  retain the normal type icon. They do not mark the game file corrupted or block exports.
- No game content is modified, and this does not implement the later Thumbnail Maker
  feature or selecting a replacement image from the gallery.

## Verification and remaining phone checks

- Host JVM 17: the existing 24 read-path checks and 26 export-path checks passed.
- `PreviewImagesCheck`: 23 synthetic cases passed for PNG headers/dimensions, bounded
  reads, exact limits, ZIP extraction, missing previews, unsafe member names, oversized
  entries/archives, CRC mismatch, and byte-for-byte preservation of the source archive.
- Two additional host checks use the supplied map and its independently extracted PNG;
  the Java extractor returns the same image bytes and leaves the archive unchanged.
- These 23 synthetic preview checks join the existing 50 checks in the GitLab build gate.
  Private sample-based checks run locally only, and the game files are not CI artifacts.
- XML, the existing 117 translated strings, and string references are checked locally.
- Full Android compilation, signing, Android `JsonReader`, `BitmapFactory`, Compose layout,
  and Shizuku/Binder thumbnail reads still require GitLab and phone verification. No local
  Android build or emulator result is claimed.

After the signed 0.1.3 build succeeds, install over the existing app. Confirm the actual
images on the three supplied file types, fast scrolling and folder refresh, graceful
fallback for a file without an icon, Arabic/English placement, and organized exports under
`Download/MelKit`. Restoring files and the wider P1 acceptance remain unimplemented.

References: [Android JsonReader](https://developer.android.com/reference/android/util/JsonReader),
[Compose Image](https://developer.android.com/reference/kotlin/androidx/compose/foundation/package-summary).
