# World template and player-progress findings — 2 November 2026

## Supplied world package

`cake driver.melworld` is a 599,307-byte ZIP archive with SHA-256
`5078f9b1a3212713e0fb5aa5e40356521020a53d1fdaf4ee9b2b8e27accc29f0`.

The observed root metadata identifies `WorldName=cake driver`, a world ID, `AppVersion=37.5`,
`IsTemplate=true`, `ForceOffline=true`, and `PreviewImage.AssetId=WorldPreviewImage`.
The package includes root `Data`/`MetaData`, a map under `Maps/Untitled`, and a start save
under `Saves/WorldStartSave`. This sample is therefore a packaged world template containing
its map and initial scene, rather than a loose directory of the current player's progress.

Its `WorldPreviewImage` is a verified PNG, 512×512 pixels and 320,569 bytes. Version 0.1.4
reads that exact member, labels `.melworld` as a world file in Arabic and English, and
shows the embedded thumbnail through the existing read-only endpoint. The PNG is larger
than the initial 256 KiB cap, so previews now permit up to 512 KiB and remain serialized.

## Supplied player-progress folder

The user's screenshots show a separate path:
`Worlds/Progress/anonymous/melon farm_09bb44e4-0ead-4e3d-9a9c-039e1d291215`.
The supplied ZIP of that folder is 1,015 bytes and contains:

| File | Actual bytes | Observations |
| --- | --- | --- |
| `Data.json` | 647 | Binary data, not parseable as ordinary UTF-8 JSON; internal structure not established |
| `EventJournal.meljournal` | 130 | `LNRJ` header and dictionary entries including `Currency_silver`, `Currency_gold`, `GlobalVariable___world_launches__` and `GlobalVariable___shared__` |

The names and observed journal keys support its role as world player-progress data.
These entries are field names, not decoded balances or confirmed progress values. The
binary file must be retained byte for byte in future progress backup experiments.
No independently extractable PNG preview was found in this folder.

The world package is Cake Driver, while the progress folder is Melon Farm. Their observed
IDs differ, so they do not establish how a matching world and progress folder should be
paired. The meaning of `anonymous` and any account/cloud association remain unverified.

## Scope and evidence

- The existing file browser already reads and exports regular world files without parsing
  or rewriting their contents. Screenshot evidence showed them with the old Other file label.
- 0.1.4 adds world classification and preview reading only; it does not combine player
  progress with a template, infer account ownership, rewrite binary data, or implement restore.
- Host tests verify the actual world preview bytes against an independent extraction and
  check that the source archive remains unchanged. Synthetic tests cover a valid PNG above
  256 KiB and keep map/world preview member selection distinct.
- Full Android compilation and the world label/image on the phone await GitLab/device
  verification. Restoring a template and its matching player progress needs a matching
  sample and an end-to-end game test before claiming complete-world backup support.

The user-supplied game files and extracted images are not committed or shipped in the APK.
