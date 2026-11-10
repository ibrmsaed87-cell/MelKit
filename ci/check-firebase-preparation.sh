#!/usr/bin/env bash
set -euo pipefail
# Synthetic configuration only; never read or print the real CI secret.
melkit_source_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
melkit_test_dir="$(mktemp -d)"
trap 'rm -rf "$melkit_test_dir"' EXIT
mkdir -p "$melkit_test_dir/ci" "$melkit_test_dir/app" "$melkit_test_dir/tmp"
cp "$melkit_source_dir/ci/prepare-firebase.sh" "$melkit_source_dir/ci/check-firebase.sh" "$melkit_test_dir/ci/"
printf '%s' '{"client":[{"client_info":{"android_client_info":{"package_name":"com.spinel.melkit"}}}]}' > "$melkit_test_dir/expected.json"
melkit_encoded="$(base64 < "$melkit_test_dir/expected.json" | tr -d '\r\n')"
melkit_checks=0

melkit_expect_success() {
  local melkit_label="$1" melkit_input="$2"
  if ! (cd "$melkit_test_dir"; TMPDIR="$melkit_test_dir/tmp" GOOGLE_SERVICES_JSON_BASE64="$melkit_input" bash ci/prepare-firebase.sh) > "$melkit_test_dir/result.log" 2>&1 ||
      ! cmp -s "$melkit_test_dir/expected.json" "$melkit_test_dir/app/google-services.json"; then
    printf 'Firebase preparation check failed: %s\n' "$melkit_label" >&2
    exit 1
  fi
  melkit_checks=$((melkit_checks + 1))
}

melkit_expect_failure() {
  local melkit_label="$1" melkit_input="$2"
  printf '%s' 'previous configuration' > "$melkit_test_dir/app/google-services.json"
  cp "$melkit_test_dir/app/google-services.json" "$melkit_test_dir/previous.json"
  if (cd "$melkit_test_dir"; TMPDIR="$melkit_test_dir/tmp" GOOGLE_SERVICES_JSON_BASE64="$melkit_input" bash ci/prepare-firebase.sh) > "$melkit_test_dir/result.log" 2>&1 ||
      ! cmp -s "$melkit_test_dir/previous.json" "$melkit_test_dir/app/google-services.json"; then
    printf 'Firebase preparation check failed: %s\n' "$melkit_label" >&2
    exit 1
  fi
  melkit_checks=$((melkit_checks + 1))
}

melkit_expect_success 'single-line variable' "$melkit_encoded"
melkit_expect_success 'wrapped variable' "$(base64 < "$melkit_test_dir/expected.json")"
melkit_expect_success 'CRLF/space/tab copy' "$(printf ' \t%s\r\n' "$melkit_encoded")"
printf '%s' "$melkit_encoded" > "$melkit_test_dir/input.base64"
melkit_expect_success 'GitLab File variable' "$melkit_test_dir/input.base64"
base64 < "$melkit_test_dir/expected.json" > "$melkit_test_dir/input.base64"
melkit_expect_success 'wrapped File variable' "$melkit_test_dir/input.base64"
melkit_expect_failure 'missing variable' ''
melkit_expect_failure 'invalid characters' "${melkit_encoded}!"
melkit_expect_failure 'truncated value' "${melkit_encoded:1}"
melkit_expect_failure 'missing File variable' "$melkit_test_dir/missing.base64"
melkit_expect_failure 'wrong Android client' "$(printf '%s' '{"package_name":"other.app"}' | base64)"
melkit_expect_failure 'server credential' "$(printf '%s' '{"package_name":"com.spinel.melkit","private_key":"synthetic-test"}' | base64)"
melkit_expect_success 'valid value replaces previous configuration' "$melkit_encoded"
if [[ -n "$(find "$melkit_test_dir/tmp" -type f -print)" ]]; then
  printf '%s\n' 'Firebase preparation left temporary configuration behind.' >&2
  exit 1
fi
melkit_checks=$((melkit_checks + 1))
printf 'Firebase preparation checks passed: %s\n' "$melkit_checks"
