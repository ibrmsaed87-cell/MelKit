#!/usr/bin/env bash
set -euo pipefail

# Fail closed: this script never generates or substitutes signing keys.
for melkit_name in MELKIT_KEYSTORE_BASE64 MELKIT_STORE_PASSWORD MELKIT_KEY_ALIAS MELKIT_KEY_PASSWORD MELKIT_SIGNING_CERT_SHA256; do
  if [[ -z "${!melkit_name:-}" ]]; then
    printf 'Missing required signing variable: %s\n' "$melkit_name" >&2
    exit 1
  fi
done

if [[ ! -f app/build.gradle.kts || ! -f gradlew || ! -f gradle/wrapper/gradle-wrapper.jar ]]; then
  printf '%s\n' 'Android project and Gradle Wrapper are not ready. Complete README phase P0 first.' >&2
  exit 1
fi

melkit_expected="${MELKIT_SIGNING_CERT_SHA256//:/}"
melkit_expected="${melkit_expected,,}"
if [[ ! "$melkit_expected" =~ ^[a-f0-9]{64}$ ]]; then
  printf '%s\n' 'MELKIT_SIGNING_CERT_SHA256 must contain the original certificate SHA-256 fingerprint.' >&2
  exit 1
fi

umask 077
melkit_signing_dir="$(mktemp -d)"
trap 'rm -rf "$melkit_signing_dir"' EXIT
export MELKIT_KEYSTORE_PATH="$melkit_signing_dir/melkit-release.jks"
printf '%s' "$MELKIT_KEYSTORE_BASE64" | base64 --decode > "$MELKIT_KEYSTORE_PATH"
unset MELKIT_KEYSTORE_BASE64

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
: "${ANDROID_COMPILE_SDK:?Set ANDROID_COMPILE_SDK.}"
: "${ANDROID_BUILD_TOOLS:?Set ANDROID_BUILD_TOOLS.}"
: "${ANDROID_COMMAND_LINE_TOOLS:?Set ANDROID_COMMAND_LINE_TOOLS.}"
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

# The Android module must use the signing/version environment variables above.
# P0 also verifies the resulting APK certificate and the install-over path.
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$CI_PROJECT_DIR/.gradle}"
melkit_sdkmanager="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$melkit_sdkmanager" ]]; then
  apt-get update -qq
  apt-get install -y -qq curl unzip >/dev/null
  mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
  melkit_tools_dir="$(mktemp -d "$melkit_signing_dir/tools.XXXXXX")"
  curl --fail --location --silent --show-error \
    "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_COMMAND_LINE_TOOLS}_latest.zip" \
    --output "$melkit_tools_dir/tools.zip"
  unzip -q "$melkit_tools_dir/tools.zip" -d "$melkit_tools_dir/unpacked"
  mv "$melkit_tools_dir/unpacked/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
fi

# Ignore yes's normal SIGPIPE, while retaining sdkmanager's actual exit status.
set +o pipefail
yes | "$melkit_sdkmanager" --licenses >/dev/null
set -o pipefail
"$melkit_sdkmanager" "platform-tools" "platforms;android-${ANDROID_COMPILE_SDK}" "build-tools;${ANDROID_BUILD_TOOLS}"

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
