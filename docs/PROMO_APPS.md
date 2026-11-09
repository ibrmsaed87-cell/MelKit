# More Apps icons and MMKB link — 9 November 2026

The user confirmed that the FCM notification arrived and opened its link, then
requested real app icons and the anticipated MMKB Play URL. These changes are
queued with removal of the notification settings card on
`codex/melkit-phone-review-20261109`; no new build was requested for this edit.

| Card | Destination | Embedded icon source |
| --- | --- | --- |
| MMIX | `https://play.google.com/store/apps/details?id=com.spinel.mmix` | Icon served by the app's Google Play listing, copied unchanged as `promo_mmix.png` (240×240). |
| MCFX | `https://play.google.com/store/apps/details?id=com.spinel.mcpackfixer` | Icon served by the app's Google Play listing, copied unchanged as `promo_mcfx.png` (240×240). |
| MMKB | `https://play.google.com/store/apps/details?id=com.spinel.mcbk` | Original Minecraft-Backup launcher icon, `app/src/main/res/mipmap-xxxhdpi/ic_launcher.png`, blob `92fefe88f42e6c303cedecf5b2ef68b2e264c679` (192×192). |
| More apps | `https://play.google.com/store/apps/dev?id=7189513262046406321` | Existing Play icon from Minecraft-Backup, `app/src/main/res/drawable-nodpi/mcbk_icon_play.webp`, blob `fec3d3aca58b61994925cca37e71d7fe5e8718ce` (180×181). |

The displayed MMKB name is retained. Its Android package is `com.spinel.mcbk`.
The anticipated URL and enabled button were explicitly requested; no claim is
made that the store page is publicly available yet. Both published MMIX and
MCFX pages were resolved, but the MMKB page could not be verified by retrieval.

All four assets are copied without editing and embedded in `drawable-nodpi`.
Compose displays them at 48dp with Fit scaling and rounded corners, with no
tint. Labels remain translated and the four-card layout is retained. No runtime
image fetcher, cache, permission or network dependency was introduced.

The source images were visually inspected and their decoded formats/dimensions
checked. XML/resource references, translations, and changed Kotlin syntax were
checked. Android layout and taps need phone verification after the next build.
