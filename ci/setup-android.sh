#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT.}"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_COMPILE_SDK="${ANDROID_COMPILE_SDK:-36}"
export ANDROID_BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-36.0.0}"
export ANDROID_COMMAND_LINE_TOOLS="${ANDROID_COMMAND_LINE_TOOLS:-11076708}"
melkit_sdkmanager="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

if [[ ! -x "$melkit_sdkmanager" ]]; then
  if ! command -v curl >/dev/null || ! command -v unzip >/dev/null; then
    apt-get update -qq
    apt-get install -y -qq curl unzip >/dev/null
  fi
  mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
  melkit_sdk_tmp="$(mktemp -d)"
  curl --fail --location --silent --show-error --retry 2 --connect-timeout 30 \
    "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_COMMAND_LINE_TOOLS}_latest.zip" \
    --output "$melkit_sdk_tmp/tools.zip"
  unzip -q "$melkit_sdk_tmp/tools.zip" -d "$melkit_sdk_tmp/unpacked"
  mv "$melkit_sdk_tmp/unpacked/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  rm -rf "$melkit_sdk_tmp"
fi

# Ignore yes's expected SIGPIPE, but keep sdkmanager's real exit status.
set +o pipefail
yes 2>/dev/null | "$melkit_sdkmanager" --licenses >/dev/null
set -o pipefail
"$melkit_sdkmanager" "platform-tools" "platforms;android-${ANDROID_COMPILE_SDK}" "build-tools;${ANDROID_BUILD_TOOLS}"
