#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${GOOGLE_SERVICES_JSON_BASE64:-}" ]]; then
  printf '%s\n' 'Missing GOOGLE_SERVICES_JSON_BASE64. Add the Firebase configuration as a CI secret.' >&2
  exit 1
fi

# Decode into a private temporary file, then validate before replacing configuration.
# Never print the secret or the decoded Firebase configuration.
umask 077
melkit_firebase_temp="$(mktemp)"
trap 'rm -f "$melkit_firebase_temp"' EXIT
if ! printf '%s' "$GOOGLE_SERVICES_JSON_BASE64" | base64 --decode > "$melkit_firebase_temp" 2>/dev/null; then
  printf '%s\n' 'GOOGLE_SERVICES_JSON_BASE64 is not valid Base64.' >&2
  exit 1
fi
unset GOOGLE_SERVICES_JSON_BASE64

bash ci/check-firebase.sh "$melkit_firebase_temp"
mkdir -p app
mv -f "$melkit_firebase_temp" app/google-services.json
