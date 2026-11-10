# MelKit AdMob and UMP — source integration, no new APK

The user approved interstitial and app-open ads only, with the strategy below,
and asked to retrieve the IDs from the latest Gmail message. The Google AdMob
implementation email dated 9 November 2026, 17:53 UTC supplies these public IDs
(the dashboard label in that email is `MilKit`; the app remains MelKit).

| Purpose | ID |
| --- | --- |
| AdMob app | `ca-app-pub-9118481973136364~9451461448` |
| Interstitial | `ca-app-pub-9118481973136364/5379133301` |
| App open | `ca-app-pub-9118481973136364/5512216436` |

Only these two formats are integrated. Dependencies are Google Mobile Ads
`25.5.0` and UMP `4.0.0`, matching the official integration documentation read
on 9 November 2026. The existing API 24 minimum / compile SDK 36 are retained.
No Analytics, banner, native or rewarded SDK integration is added.

## Test serving and release serving

`MELKIT_LIVE_ADS` defaults to `false`. Consequently signed phone-test builds
use Google's dedicated test units, including Release builds. Live IDs remain
configured for a later release build explicitly using `MELKIT_LIVE_ADS=true`.
Unexpected values fail Gradle configuration. The AdMob application ID is the
real app ID so UMP can fetch this app's configured privacy messages.
The build report records `Live AdMob units: true/false` so a phone-test artifact
and a production artifact can be distinguished before distribution.

No Gradle, APK/AAB or Android CI build was run for this source integration.
The source version name is prepared as `0.1.14`; GitLab still supplies its
increasing version code when a build is authorized. The package and original
signing configuration remain unchanged.
Firebase's recent `GOOGLE_SERVICES_JSON_BASE64` migration is included:
`app/google-services.json` remains untracked and restored by CI before checking
or building. Never bring back the old Git ignore exception or its tracked file.

## Interstitial strategy

- Every verified successful task is a presentation opportunity, including the
  first task of a normal visit; two successful tasks are no longer required.
  A whole restore batch counts once.
- A separate minimum of two observed in-app user actions is required since the
  last full-screen exposure/normal visit. Completed tasks do not manufacture
  action credit. MainActivity counts handled gesture releases and activation/
  Back key releases only while the host is resumed, with no consent or ad UI.
  Move events, extra fingers, cancellations, SDK/load callbacks and synthetic
  task callbacks do not create action credit. Input in another window (such as
  a dialog or accessibility-only activation) may be conservatively uncounted;
  this can skip an opportunity, never force an extra ad. No raw input is saved.
- At least 50 seconds must pass after closing either full-screen format.
- Maximum three actual interstitial exposures in a rolling five-minute window.
  Exposure history and the shared cooldown survive process restarts. Clock
  rollback does not remove future exposure timestamps or bypass cooldowns.
- The show callback persists a fallback cooldown immediately. A real dismissal
  extends it to 50 seconds after closing; process death before that callback
  cannot discard the minimum gap between formats.
- Opportunities are after verified backup/export, saved/received-file restore,
  fully successful batch restore, verified bundle creation, successful profile
  application, and explicit scan completion, before publishing their results.
- Failed, conflicted, cancelled or partially restored batches do not create an
  opportunity. Pagination, navigation, Back, setup, settings and ad dismissal
  are not tasks. Profile undo/delete and network-transfer completion are not
  advertising triggers.
- The current route must match the task's origin. Background completion cannot
  show or queue an ad on another screen. A live network transfer always blocks
  full-screen ads, even if UI state has not recomposed yet.
- The SDK must already have a fresh loaded ad. Otherwise publish the result
  immediately. Loading callbacks never present an ad, and no timer launches one.
- File writes and verification finish before the advertising boundary. Export
  rollback ends before advertising, so an SDK issue cannot remove a verified
  published backup. Closing/failing an ad releases the pending result once;
  destroying its host also releases it so a result cannot remain blocked.

These are the user's approved starting settings, not Google policy thresholds,
an income guarantee or an assertion that the app has passed an AdMob review.

## App-open strategy

- Eligible after three normal foreground visits; rotation, permission handling,
  notification handling and returning from an externally launched activity or
  ad are excluded from the normal-visit opportunity.
- Maximum one app-open exposure per ten minutes, with the same 50-second gap
  shared with interstitials. Never stack formats.
- Presentation is attempted synchronously at foreground resume, only on an
  idle Home screen and with a live foreground host. Work, transfer and consent
  UI block it. All other routes are deliberately excluded.
- Starting an external activity or activity-result flow suppresses app-open on
  return, including MMIX, Shizuku, the document/photo pickers, sharesheets and
  the tutorial activity. Notification taps retain direct-link behavior.
- A fresh process has no cached ad: it continues without waiting, and preloads
  for a later foreground opportunity. An ad that arrives after entry never
  interrupts already-visible content.
- Expiry: interstitial one hour; app-open four hours. A successfully consumed ad
  can be replenished immediately; the old blanket one-minute load throttle no
  longer discards this preload opportunity after a short ad.
- Failed/no-fill requests retry after 60, 120, 240, 480, then at most 900 seconds,
  using the monotonic device clock. There is at most one pending retry callback.
  Retries only populate the cache and never display an ad. They stop when the
  host pauses, privacy changes, a full-screen ad is active or transfer is active;
  returning to the app or ending a transfer can resume cache preparation.

## UMP client behavior

- Request fresh consent information on Activity/app startup, display a required
  form using `loadAndShowConsentFormIfRequired`, and check `canRequestAds()`
  before SDK initialization, requests or presentation. A consent error cannot
  block the app's file functionality or bypass UMP's request state.
- No manual country, IP or language-based bypass is implemented. The published
  AdMob message targeting and UMP determine whether a form is required.
- Settings exposes **Privacy choices / خيارات الخصوصية** only when UMP says
  `privacyOptionsRequirementStatus == REQUIRED`. This also permits applicable
  US state opt-out controls without forcing the European startup form globally.
- Privacy changes invalidate cached ads. Callback generations prevent an old
  Activity or consent decision from repopulating the cache. No ad is displayed
  while a form is in use.
- Ad creative content is capped at rating T. No child-directed or under-age
  consent status is inferred solely from the store's age rating.

## AdMob dashboard status

After the original login attempt failed, the user reported saving the existing
published UMP message with 17 selected apps instead of 12 on 9 November 2026.
Do not treat that old login failure as an outstanding blocker or repeat setup.
This is user-confirmed dashboard work, not a new authenticated inspection.

The release/device review should confirm the message for the app ID above has:

1. Target **EEA, UK and Switzerland**, rather than Everywhere.
2. English as default plus Arabic; review both previews.
3. Privacy policy `https://spinel.info/PrivacyPolicy.html`.
4. Clear consent, decline and manage-options choices; publish the message.
5. Review applicable US state privacy settings separately if used. Their opt-out
   options are distinct from the European startup form.

Optional matching dashboard unit caps: interstitial 3 / 5 minutes and app-open
1 / 10 minutes. The code enforces these caps without relying on the dashboard.
Do not set a combined app-wide cap of three and assume it is interstitial-only.

## Verification completed and next phone checks

On 10 November the host suite passed all **483 checks**, including 47 ad-policy
checks and 14 loading/retry checks. The updated boundary cases cover 49,999 vs
50,000 ms after dismissal and 299,999 vs 300,000 ms for the rolling interstitial
cap, first-task eligibility, the separate two-action guard, and unchanged
app-open ten-minute cap. They also cover
immediate replenishment after success, failure boundaries, capped backoff,
reset after recovery and independence of the two formats, in addition to the
existing rolling-cap and app-open rules. Changed AdManager/MainActivity Kotlin
syntax, nine XML files, 389 matching translatable resource keys/placeholders,
CI shell syntax and Git diff checks passed. The original source integration
also checked eight Kotlin files, 384 string references, drawable references
and the email IDs.
Firebase remains untracked and ignored. These do not establish Android compilation,
SDK network responses, live ad fill, regional form delivery or phone behavior.

After dashboard setup and an explicitly authorized combined test build, check:

- Both existing MMIX/version-card edits, Arabic/English, installation over the
  previous APK, and FCM links.
- Google's test-ad labels, first-task opportunities, two-action guard, timing thresholds,
  and direct results with no ad/no internet.
- After a short test ad, verify replacement loading without waiting for a new
  user task. Simulate no-fill/offline and recovery; retries must never show ads
  on their own, or run in the background/during a direct transfer.
- Home foreground ads after normal visits; no ad on external return, notification
  taps, rotation, consent, file operations or two-phone transfer.
- Success results after ad dismissal or show failure; no lost copy or repeated
  restore, and one opportunity for a whole successful batch.
- EEA/UK/Swiss required form, a non-EEA test location, saved consent and its
  withdrawal entry. Force test geography only for registered test devices.
- Switch to live serving only for the release build after successful tests.

The 50-second gap and 3/5-minute interstitial cap are the user's product settings, not Google's
numeric guarantee of compliance or maximum revenue. Google's policy disallows
interstitials after every action, specifies no more than one after two user
actions, and requires natural breaks. A successful task is distinct from a
single click: navigation, selection and confirmation can provide multiple
interactions before that task ends. Time and cap alone do not replace this
guard. Evaluate real
revenue and continued use after release before changing these settings.

## Primary references

- https://developers.google.com/admob/android/quick-start
- https://developers.google.com/admob/android/interstitial
- https://developers.google.com/admob/android/app-open
- https://developers.google.com/admob/android/privacy
- https://support.google.com/admob/answer/6201362
- https://support.google.com/admob/answer/9341964
- https://support.google.com/admob/answer/10113207
