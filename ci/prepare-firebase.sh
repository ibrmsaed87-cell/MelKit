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
melkit_firebase_encoded="$GOOGLE_SERVICES_JSON_BASE64"
unset GOOGLE_SERVICES_JSON_BASE64
# A GitLab File variable contains the temporary file path rather than its value.
if [[ "$melkit_firebase_encoded" == /* && ${#melkit_firebase_encoded} -le 4096 ]]; then
  if [[ ! -f "$melkit_firebase_encoded" || ! -r "$melkit_firebase_encoded" ]]; then
    printf '%s\n' 'The GOOGLE_SERVICES_JSON_BASE64 File variable is not readable.' >&2
    exit 1
  fi
  melkit_firebase_encoded="$(cat "$melkit_firebase_encoded")"
fi
# Handle wrapped/CRLF values copied from a phone without ignoring other garbage.
melkit_firebase_encoded="$(printf '%s' "$melkit_firebase_encoded" | tr -d '[:space:]')"
if [[ -z "$melkit_firebase_encoded" || ! "$melkit_firebase_encoded" =~ ^[A-Za-z0-9+/]+={0,2}$ ]] ||
    (( ${#melkit_firebase_encoded} % 4 != 0 )); then
  printf '%s\n' 'GOOGLE_SERVICES_JSON_BASE64 is not valid Base64.' >&2
  exit 1
fi
# -d is supported by both Alpine BusyBox and GNU coreutils.
if ! printf '%s' "$melkit_firebase_encoded" | base64 -d > "$melkit_firebase_temp" 2>/dev/null; then
  printf '%s\n' 'Could not decode GOOGLE_SERVICES_JSON_BASE64 with this runner.' >&2
  exit 1
fi
unset melkit_firebase_encoded

bash ci/check-firebase.sh "$melkit_firebase_temp"
mkdir -p app
mv -f "$melkit_firebase_temp" app/google-services.json
