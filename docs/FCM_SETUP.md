# MelKit push notifications — 9 November 2026

## Configuration and scope

- Package: `com.spinel.melkit`; Firebase project: `scangpapp`.
- The user supplied `google-services.json` and authorized its integration. Only
  the matching MelKit Android client is included in `app/google-services.json`;
  the other three app clients were omitted. The SDK app ID, project info and
  client API configuration are unchanged for MelKit. This is Android client
  configuration, not a service-account key or server credential.
- Google Services plugin `4.5.0`, Firebase BoM `35.1.0`, `firebase-messaging`.
  No Analytics, AdMob or server/admin dependency was added.
- Topic and notification channel: `melkit_updates`. This topic is specific to
  MelKit even though the Firebase project hosts several apps.
- `POST_NOTIFICATIONS` is requested once on normal first launch on Android 13+.
  Refusal leaves the app usable. Settings show the permission/channel state and
  link to Android notification settings without repeatedly prompting.
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

- **422 host checks passed**, including 53 Play Store URL/package validation
  cases and all 369 earlier checks.
- Firebase JSON selects exactly one `com.spinel.melkit` client. XML parsed,
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

## Primary references

- https://firebase.google.com/docs/android/setup
- https://firebase.google.com/docs/cloud-messaging/android/receive-messages
- https://firebase.google.com/docs/cloud-messaging/android/get-started
- https://developer.android.com/develop/ui/compose/notifications/notification-permission
- https://firebase.google.com/docs/projects/api-keys
