# MelKit push notifications — 9 November 2026

## Configuration and scope

- Package: `com.spinel.melkit`; Firebase project: `scangpapp`.
- The user supplied `google-services.json` and authorized its integration.
  The configuration is no longer tracked in Git. The user reported adding its
  Base64 content as `GOOGLE_SERVICES_JSON_BASE64` in GitHub Actions secrets and
  GitLab CI/CD variables. GitHub retains a backup; Android builds run only in
  GitLab. `ci/prepare-firebase.sh` restores `app/google-services.json` from that
  variable in each validation/build job, checks for the MelKit Android client,
  and rejects server credentials before replacing any local configuration.
  A configuration containing other Android clients is accepted; Google Services
  selects `com.spinel.melkit`. Its SDK app ID, project info and client API
  configuration remain unchanged. Base64 is encoding, not encryption.
- Google Services plugin `4.5.0`, Firebase BoM `35.1.0`, `firebase-messaging`.
  No Analytics, AdMob or server/admin dependency was added.
- Topic and notification channel: `melkit_updates`. This topic is specific to
  MelKit even though the Firebase project hosts several apps.
- `POST_NOTIFICATIONS` is requested once on normal first launch on Android 13+.
  Refusal leaves the app usable. At the user's request, there is no in-app
  notification settings card; the standard Android Allow/Don't allow prompt is
  the only notification-permission UI. The first launch is recorded even when
  permission was already granted, and sharing no longer asks for it again.
  Users can later change their choice in Android's app notification settings.
- The permanent signing key and application ID are unchanged. Version name is
  `0.1.12`; CI continues to assign the increasing version code.

## Link payload

Use custom data key **`url`** with an app-detail URL, for example:

```json
{"url":"https://play.google.com/store/apps/details?id=com.spinel.mmix"}
```

`link` and `play_url` are also accepted. `package_name` can instead contain a
valid Android app ID. Only the first non-empty supported field is considered;
an invalid explicit link does not fall through to another field.

Only HTTPS URLs on the exact `play.google.com` host, at `/store/apps/details`
with one valid `id`, are accepted. The app reconstructs a canonical app URL,
dropping extra tracking/language parameters. Other schemes, hosts, userinfo,
ports, fragments, missing or duplicate IDs and invalid app IDs are rejected.
Store navigation prefers the Play Store app and falls back to the validated
HTTPS URL. Unusable links open MelKit rather than another external target.

Messages with notification+data from Firebase Console open through launcher
extras when the app is in the background. Foreground and data-only legacy FCM
messages are displayed by `MelKitMessagingService` with immutable, individually
identified PendingIntents. Receipt never launches a link. On tap, MainActivity
validates and consumes the link before opening it, avoiding reopening on
rotation/return. Normal messages without a link open MelKit.

This uses the stable Firebase SDK's existing AndroidConfig message handling,
not the separate AndroidConfigV2 early-access SDK. No notification campaign or
test push has been sent by this implementation task.

## Verification and phone test

- Secret migration checks on 9 November 2026 passed: the user's Base64 decodes
  byte-for-byte to the supplied file and preserves the existing MelKit client
  and Firebase project. The preparation script creates a mode-0600 file, rejects
  missing/empty/invalid Base64, a different app and server credentials, preserves
  an existing file on those failures, and does not print the secret. Bash syntax,
  CI YAML, preparation order and Git ignore rules passed. Android compilation
  and the values stored remotely have not been verified in a new pipeline;
  no APK/AAB build was started for this migration.
- **422 host checks passed**, including 53 Play Store URL/package validation
  cases and all 369 earlier checks.
- The initial tracked Firebase JSON selected exactly one MelKit client. XML parsed,
  386 translated keys and 382 Kotlin string references verified; six changed
  or added Kotlin files passed syntax parsing. CI shell scripts passed syntax
  and Firebase configuration checks.
- Android compilation, permission UI, actual delivery, PendingIntents and
  foreground/background behavior require the new GitLab APK and phone test.
- On the phone: allow notifications, then send a notification+custom-data test
  for MelKit from Firebase Console. Test app open, app in background, normal
  closed-app launch, denial and later enabling in settings, two notifications
  with different app URLs, and invalid/absent URLs. Tap only after receiving;
  verify the expected Play page and no repeat after returning/rotation.
- Test the four previous UI/restore changes in the same APK. Check install-over
  with the original signing certificate. Force-stopping an Android app is a
  separate delivery limitation, not the normal closed-app scenario above.

## Notification UI correction after phone review

The user requested removing the notification card and using the first-launch
system permission prompt. The card, its helper and three unused translations
per language were removed. Registration, the OS channel, message display and
validated Play-link navigation remain in place. The first-launch flag now also
records an already-granted permission, and sharing requests network permission
only. XML, translated keys, resource references and changed Kotlin syntax were
reviewed for this correction; no extra APK build is part of this UI edit.

## Primary references

- https://firebase.google.com/docs/android/setup
- https://firebase.google.com/docs/cloud-messaging/android/receive-messages
- https://firebase.google.com/docs/cloud-messaging/android/get-started
- https://developer.android.com/develop/ui/compose/notifications/notification-permission
- https://firebase.google.com/docs/projects/api-keys

