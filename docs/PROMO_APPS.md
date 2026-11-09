# More Apps icons and MCBK link — 9 November 2026

The user confirmed that the FCM notification arrived and opened its link, then
requested real app icons and the anticipated MCBK Play URL. These changes are
first queued with removal of the notification settings card on
`codex/melkit-phone-review-20261109`, then included in the 0.1.13 snapshot on
main (`2936c667ca602a387395fc0b79f9954f158b1a6d`). The user's settings
screenshot confirms that 0.1.13 runs and the notification card is gone; the
promo icons themselves have not yet been confirmed on the phone.

| Card | Destination | Embedded icon source |
| --- | --- | --- |
| MMIX | `https://play.google.com/store/apps/details?id=com.spinel.mmix` | Icon served by the app's Google Play listing, copied unchanged as `promo_mmix.png` (240×240). |
| MCFX | `https://play.google.com/store/apps/details?id=com.spinel.mcpackfixer` | Icon served by the app's Google Play listing, copied unchanged as `promo_mcfx.png` (240×240). |
| MCBK | `https://play.google.com/store/apps/details?id=com.spinel.mcbk` | Original Minecraft-Backup launcher icon, `app/src/main/res/mipmap-xxxhdpi/ic_launcher.png`, blob `92fefe88f42e6c303cedecf5b2ef68b2e264c679` (192×192). |
| More apps | `https://play.google.com/store/apps/dev?id=7189513262046406321` | Existing Play icon from Minecraft-Backup, `app/src/main/res/drawable-nodpi/mcbk_icon_play.webp`, blob `fec3d3aca58b61994925cca37e71d7fe5e8718ce` (180×181). |

The displayed name is now MCBK, corrected from the earlier MMKB card label at the user's request. Its Android package is `com.spinel.mcbk`.
The anticipated URL and enabled button were explicitly requested; no claim is
made that the store page is publicly available yet. Both published MMIX and
MCFX pages were resolved, but the MCBK page could not be verified by retrieval.

All four assets are copied without editing and embedded in `drawable-nodpi`.
Compose displays them at 48dp with Fit scaling and rounded corners, with no
tint. Labels remain translated and the four-card layout is retained. No runtime
image fetcher, cache, permission or network dependency was introduced.

The source images were visually inspected and their decoded formats/dimensions
checked. XML/resource references, translations, and changed Kotlin syntax were
checked. Android layout and taps need phone verification after the next build.

## Home companion card and version card — no build

The user approved the proposed MMIX card above **Your library** and requested
implementation together with a version card matching Appearance. They explicitly
deferred the next build until after AdMob is added. These edits are saved on
`codex/melkit-mmix-home-version-card-20261109`; main and the installed APK remain
unchanged for this step. Version and signing configuration are unchanged.

- The home card uses the embedded original MMIX icon, a localized explanation,
  mint outline and an adaptive label: **Open MMIX** / **Download MMIX**.
- Both the card and its button use the same action. The app checks for a launcher
  for `com.spinel.mmix` on entry, on foreground resume and again on tap. An
  installed, launchable MMIX opens directly. If unavailable or launching fails,
  the exact URL `https://play.google.com/store/apps/details?id=com.spinel.mmix`
  opens in Google Play, with a browser fallback if Play cannot handle it. If no
  handler exists, the existing localized link error is shown.
- The manifest queries this one additional package. It does not request
  `QUERY_ALL_PACKAGES`, enumerate installed apps, fetch icons or introduce an SDK.
- The existing More Apps tab retains its four cards and store actions.
- Settings now uses the same `ToolText` Surface, corner radius, spacing and colors
  for Appearance and App version, with `BuildConfig.VERSION_NAME` as its value.
- The home card has no fixed height or truncated copy; it follows RTL/LTR and
  the dashboard scrolls within its existing fixed navigation.

Validation completed without Gradle or an APK/AAB build: changed Kotlin syntax,
nine XML files, 387 matching translation keys/placeholders, 382 app string
references and drawable references. The manifest and unchanged version were
checked, and `git diff --check` passed. No new tests were added for this small
UI/navigation change, and no Android execution is claimed.

After the combined AdMob build, verify both languages, the version-card layout,
the whole-card and button actions with MMIX installed and absent, and the label
refresh after returning from a store installation. Also check the browser
fallback on a device without Play, if available. Do not remove MelKit to test.

Android implementation references:

- [Targeted package visibility](https://developer.android.com/training/package-visibility/declaring)
- [PackageManager launcher contract](https://developer.android.com/reference/android/content/pm/PackageManager#getLaunchIntentForPackage(java.lang.String))
