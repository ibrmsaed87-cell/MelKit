#!/usr/bin/env bash
set -euo pipefail

# Fail closed: this script never generates or substitutes signing keys.
umask 077
melkit_signing_dir="$(mktemp -d)"
trap 'rm -rf "$melkit_signing_dir"' EXIT
export MELKIT_KEYSTORE_PATH="$melkit_signing_dir/melkit-release.jks"

if [[ -n "${MELKIT_SIGNING_BUNDLE:-}" ]]; then
  printf '%s' "$MELKIT_SIGNING_BUNDLE" | base64 --decode > "$melkit_signing_dir/bundle.tar.gz"
  unset MELKIT_SIGNING_BUNDLE
  # Extract fixed members to stdout only. Never extract paths from the archive.
  tar -xOzf "$melkit_signing_dir/bundle.tar.gz" melkit-release.jks > "$MELKIT_KEYSTORE_PATH"
  export MELKIT_STORE_PASSWORD="$(tar -xOzf "$melkit_signing_dir/bundle.tar.gz" store-password)"
  export MELKIT_KEY_PASSWORD="$(tar -xOzf "$melkit_signing_dir/bundle.tar.gz" key-password)"
  export MELKIT_KEY_ALIAS="$(tar -xOzf "$melkit_signing_dir/bundle.tar.gz" key-alias)"
  melkit_package="$(tar -xOzf "$melkit_signing_dir/bundle.tar.gz" package-name)"
  [[ "$melkit_package" == com.spinel.melkit ]] || { printf '%s\n' 'Signing bundle belongs to another application.' >&2; exit 1; }
else
  : "${MELKIT_KEYSTORE_BASE64:?Add MELKIT_SIGNING_BUNDLE or the original individual signing variables.}"
  printf '%s' "$MELKIT_KEYSTORE_BASE64" | base64 --decode > "$MELKIT_KEYSTORE_PATH"
  unset MELKIT_KEYSTORE_BASE64
fi

for melkit_name in MELKIT_STORE_PASSWORD MELKIT_KEY_ALIAS MELKIT_KEY_PASSWORD; do
  if [[ -z "${!melkit_name:-}" ]]; then
    printf 'Missing required signing variable: %s\n' "$melkit_name" >&2
    exit 1
  fi
done

if [[ ! -f app/build.gradle.kts || ! -f gradlew || ! -f gradle/wrapper/gradle-wrapper.jar ]]; then
  printf '%s\n' 'Android project and Gradle Wrapper are not ready. Complete README phase P0 first.' >&2
  exit 1
fi

melkit_expected="$(tr -d '\r\n:' < docs/signing-certificate.sha256)"
melkit_expected="${melkit_expected,,}"
if [[ ! "$melkit_expected" =~ ^[a-f0-9]{64}$ ]]; then
  printf '%s\n' 'docs/signing-certificate.sha256 must contain the original certificate fingerprint.' >&2
  exit 1
fi

keytool -exportcert -keystore "$MELKIT_KEYSTORE_PATH" \
  -alias "$MELKIT_KEY_ALIAS" -storepass:env MELKIT_STORE_PASSWORD \
  -file "$melkit_signing_dir/certificate.der" >/dev/null
read -r melkit_actual _ < <(sha256sum "$melkit_signing_dir/certificate.der")
if [[ "$melkit_actual" != "$melkit_expected" ]]; then
  printf '%s\n' 'Signing certificate mismatch. Restore the original MelKit secrets; do not generate a replacement key.' >&2
  exit 1
fi

: "${CI_PROJECT_DIR:?Run in GitLab CI or explicitly provide CI_PROJECT_DIR.}"
: "${CI_PIPELINE_IID:?A monotonically increasing pipeline number is required.}"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT.}"
melkit_version_base="${MELKIT_VERSION_CODE_BASE:-0}"
if [[ ! "$CI_PIPELINE_IID" =~ ^[0-9]{1,9}$ || ! "$melkit_version_base" =~ ^[0-9]{1,9}$ ]]; then
  printf '%s\n' 'Version code inputs must be non-negative integers with at most 9 digits.' >&2
  exit 1
fi
export MELKIT_VERSION_CODE=$((10#$CI_PIPELINE_IID + 10#$melkit_version_base))
if (( MELKIT_VERSION_CODE < 1 || MELKIT_VERSION_CODE > 2100000000 )); then
  printf '%s\n' 'Version code is outside the supported Android/Play range.' >&2
  exit 1
fi

# APK-only runs stay in test mode; an authorized AAB release defaults to verified live units.
export MELKIT_LIVE_ADS="${MELKIT_LIVE_ADS:-${BUILD_AAB:-false}}"

# The Android module uses the signing/version environment variables above.
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$CI_PROJECT_DIR/.gradle}"
bash ci/check-content-paths.sh
bash ci/prepare-firebase.sh
unset GOOGLE_SERVICES_JSON_BASE64
bash ci/check-firebase.sh
source ci/setup-android.sh

chmod +x gradlew
melkit_tasks=(:app:assembleRelease)
if [[ "${BUILD_AAB:-false}" == true ]]; then
  melkit_tasks+=(:app:bundleRelease)
fi
./gradlew --no-daemon --console=plain "${melkit_tasks[@]}"

# Verify the produced APKs themselves; a keystore check alone is insufficient.
melkit_apksigner="$ANDROID_SDK_ROOT/build-tools/$ANDROID_BUILD_TOOLS/apksigner"
melkit_aapt="$ANDROID_SDK_ROOT/build-tools/$ANDROID_BUILD_TOOLS/aapt"
shopt -s nullglob
melkit_apks=(app/build/outputs/apk/release/*.apk)
if (( ${#melkit_apks[@]} == 0 )); then
  printf '%s\n' 'No release APK was produced.' >&2
  exit 1
fi
mkdir -p build/reports
printf 'Package: com.spinel.melkit\nVersion code: %s\nSigning certificate SHA-256: %s\n' \
  "$MELKIT_VERSION_CODE" "$melkit_expected" > build/reports/melkit-build.txt
printf 'Live AdMob units: %s\n' "${MELKIT_LIVE_ADS:-false}" >> build/reports/melkit-build.txt
for melkit_apk in "${melkit_apks[@]}"; do
  "$melkit_apksigner" verify --print-certs "$melkit_apk" > "$melkit_signing_dir/apk-signature.txt"
  melkit_apk_cert="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$melkit_signing_dir/apk-signature.txt")"
  if [[ "$melkit_apk_cert" != "$melkit_expected" ]]; then
    printf '%s\n' 'Built APK uses an unexpected signing certificate. Refusing to publish artifacts.' >&2
    exit 1
  fi
  "$melkit_aapt" dump badging "$melkit_apk" > "$melkit_signing_dir/apk-badging.txt"
  read -r melkit_package_line < "$melkit_signing_dir/apk-badging.txt"
  if [[ "$melkit_package_line" != *"name='com.spinel.melkit'"* || "$melkit_package_line" != *"versionCode='$MELKIT_VERSION_CODE'"* ]]; then
    printf '%s\n' 'Built APK package or version code does not match MelKit configuration.' >&2
    exit 1
  fi
  sha256sum "$melkit_apk" >> build/reports/melkit-build.txt
done

