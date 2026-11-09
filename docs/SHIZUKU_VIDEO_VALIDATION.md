# Offline Shizuku tutorial integration

Prepared on 9 November 2026 for the existing deferred 0.1.11 branch. The user
authorized completing the Arabic/English integration and saving it without a new
build. Main, GitLab synchronization, signing, and build configuration are unchanged.

## Resulting behavior

- The first Tools card opens the Shizuku guide. A prominent video card precedes
  the existing first-time setup, everyday-start instructions, and official download.
  The app-wide connection notice continues to link to this same guide.
- Watch opens a non-exported `ShizukuVideoActivity`, without requiring Shizuku,
  storage access, an external player, or network access. The activity reads its
  AppCompat app-locale configuration and explicitly selects the Arabic resource
  for `ar`, otherwise English. Both raw resource IDs are directly referenced.
- The native VideoView/MediaController provides play, pause, seeking, and replay.
  The video is fitted inside the available area without forcing orientation.
  Insets keep the header and video clear of system bars and cutouts.
- Playback position and user pause state are saved across activity recreation.
  Backgrounding pauses playback and hides controls; returning resumes only when
  playback was active. On destruction the player is released. A loading indicator
  and translated retry error cover preparation and decoder failures.

## Original packaged assets

The approved source files were materialized from their existing persistent file
identities. They were copied byte-for-byte, with no recompression or regeneration.

| Language | Source | App resource | Bytes | Duration |
| --- | --- | --- | ---: | ---: |
| Arabic | `MelKit_Shizuku_Setup_AR.mp4` | `res/raw/shizuku_setup_ar.mp4` | 2,756,757 | 190.106446 s |
| English | `MelKit_Shizuku_Setup_EN.mp4` | `res/raw/shizuku_setup_en.mp4` | 2,325,981 | 156.314698 s |

SHA-256:

- Arabic: `34371d259add4dd66e2d89e7e782e3a10438a231b92bffe57011f79e37b69a80`
- English: `a41258fef88c193fb58f108fe5b2cabc3d4769b250d66b67cd7d99155fbb7a77`

Combined raw size is 5,082,738 bytes (about 4.85 MiB). Actual APK size is unknown
until the approved release build. Both files contain 720×1560 H.264 High level 4.0
video at 24 fps and mono AAC-LC audio at 44.1 kHz.

## Checks completed for this change

- SHA-256 of both packaged resources exactly matches the materialized originals.
- FFmpeg decoded both complete video and audio streams with `-xerror` and no errors.
- Extracted frames at 25 seconds were visually inspected: Arabic and English
  tutorial screens match their resource names.
- Tree-sitter Kotlin syntax parsing passed for `ShizukuVideoActivity.kt` and
  `MelKitApp.kt`. This is not Kotlin type checking or Android compilation.
- All 8 application XML files parse; no duplicate strings; all 373 translated
  keys and placeholders match. All 372 string references resolve.
- Activity layout IDs resolve; the activity is registered once with
  `exported=false`; both raw resource files and direct code references exist.
- `git diff --check` passed. No Java file-operation code was changed; the previous
  317 Java assertions were not rerun solely for this UI/media integration.

No Gradle task, Android resource linking, emulator/phone run, APK/AAB build, main
merge, or GitLab synchronization was performed for this change. Host decoding
does not prove device decoding or UI behavior.

## Phone checks after an authorized build

1. Open Tools → Shizuku setup guide in Arabic, then Watch. Check Arabic video,
   controls, audio, and return to the same guide. Repeat in English.
2. Use an app language different from the phone language. Reopen the player and
   confirm it follows MelKit. Repeat with networking disabled.
3. Pause and seek, rotate, background/return, then use Back. Check the saved
   position, preserved user pause, and no audio after leaving.
4. Stop Shizuku and use Connect outside the library. Open the guide from the
   connection notice and play the video without connecting Shizuku.
5. Check portrait/landscape and enlarged text; verify no cropped video, hidden
   navigation, or overlap with system bars. If a device reports a decoder error,
   check the retry and the available text instructions.

## Primary API references

- https://developer.android.com/reference/android/widget/VideoView
- https://developer.android.com/guide/topics/resources/providing-resources
