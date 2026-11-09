# Combined phone-review APK 0.1.13

The user asked to continue with the remaining filename-direction correction
and one combined APK build. This authorizes the next GitLab test build; the
earlier requests to defer builds applied to the work collected before this step.

## Included changes

- The first saved-file restore confirmation uses `ContentOrLtr` for the filename,
  matching the conflict dialog. A Latin filename such as
  `12 cylinder engine.melsave` keeps its leading number while Arabic names follow
  their content direction. The path and stored file contents are unchanged.
- Remove the custom notification settings card. Keep the one-time system
  notification-permission request on normal first launch, record already-granted
  permission as handled, and avoid asking again from the sharing flow.
- Embed the real MMIX, MCFX and MCBK icons and the Play logo for More apps.
  Preserve all four translated cards, display the corrected MCBK name and enable
  its requested anticipated URL for `com.spinel.mcbk`. Public availability of
  that page has not been verified.
- Version name is `0.1.13`. The existing GitLab script supplies the increasing
  version code and verifies the permanent signing certificate before and after
  packaging. No signing key or application ID is changed.

Source was collected from work branch `codex/melkit-phone-review-20261109` at
`d515eaee95f2781d747afc008f91fc8a9f1ef737`. Main was
`a93b315a1a416d747b4cbb9877a8dc599638f9c0` before preparing this build.
The integrated build snapshot includes all work-branch changes.

## Verification

Before publishing this snapshot, the existing host suite passed all 422 checks.
The Firebase client check, syntax of all five CI shell scripts, nine XML files,
383 matching translated keys/placeholders, 378 application string references,
application drawable references and decoding of all four promo icons passed.
All five changed, existing Kotlin files passed the syntax parser; the removed
notification card and corrected MCBK label were also verified. `git diff --check`
passed. No new tests were added for the display text change. These checks do not
replace Android compilation or device review. Only GitLab builds the signed
release APK; AAB remains disabled for this test.

## Phone acceptance after installation over 0.1.12

1. Confirm version 0.1.13 and retained library/backups data.
2. Check that notification settings has no custom card and that an existing
   permission choice is not requested again. Test first-launch prompting on a
   separate fresh installation rather than removing the user's current app.
3. Check the original app icons, MCBK label and link in More apps in Arabic/English.
4. Open a saved-file restore confirmation for the numbered Latin filename and
   confirm its name is readable in order. Check an Arabic filename if available.
5. Confirm the already-tested FCM link tap still works. No new phone result or
   successful APK build is claimed until directly evidenced.
