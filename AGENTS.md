# MelKit contributor workflow

Read README.md before changing this project. It is the agreed scope, phase plan,
acceptance criteria, and progress record. Update it when a phase or decision changes.

- Keep application ID and namespace `com.spinel.melkit`.
- Support Arabic (RTL) and English (LTR) throughout every user-facing feature.
- Match the approved dark/mint home design; add the requested four-card More Apps tab.
- Keep MMIX's general downloaded-mod installer out of MelKit's scope.
- Prove actual Android 16 file access and restore before implementing destructive
  file operations. Prototype in the emulator, then verify actual game files on a phone.
- Never label permission/read errors or unknown formats as corrupted files.
- Preserve originals before modification, verify copies, and keep reversible quarantine.
- Keep disabled mods and backups outside app-specific storage so removing the app
  does not remove those files.
- GitHub is authoritative. Its workflow synchronizes source only; Android builds run
  in GitLab CI. Do not add GitHub APK/AAB build jobs.
- Use the permanent MelKit signing key from the first distributed APK. Never regenerate
  or replace it to fix CI. Missing or mismatched signing secrets must stop the build.
- The original key has already been created. Recover it from the private
  MelKit-signing-backup.zip deliverable if needed; never create another key.
  MELKIT_SIGNING_BUNDLE is the preferred single CI secret. The public fingerprint
  in docs/signing-certificate.sha256 must match the key and every release APK.
- Never commit credentials or signing keys, expose them in logs, or put them in artifacts.
- Keep `app/google-services.json` untracked. Restore it from the
  `GOOGLE_SERVICES_JSON_BASE64` CI secret with `ci/prepare-firebase.sh` before
  Firebase validation or Android builds; never restore the old Git exception.
- Add AdMob/UMP and Firebase/FCM during the late release-preparation phase.
- Do not claim a feature, build, phone test, remote repository, or secret setup is done
  without direct evidence. Keep planned work visibly separate from completed work.

