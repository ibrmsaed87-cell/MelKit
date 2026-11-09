#!/usr/bin/env bash
set -euo pipefail
melkit_firebase_file="${1:-app/google-services.json}"
if [[ ! -s "$melkit_firebase_file" ]]; then
  printf '%s\n' 'Missing the approved MelKit Firebase Android client configuration.' >&2
  exit 1
fi
if ! grep -Eq '"package_name"[[:space:]]*:[[:space:]]*"com\.spinel\.melkit"' "$melkit_firebase_file"; then
  printf '%s\n' 'Firebase configuration does not contain the MelKit Android client.' >&2
  exit 1
fi
if grep -Eq '"private_key"|"type"[[:space:]]*:[[:space:]]*"service_account"' "$melkit_firebase_file"; then
  printf '%s\n' 'Server credentials must never be packaged in the app.' >&2
  exit 1
fi
# The Google Services Gradle task also parses JSON and matches the application ID.
printf '%s\n' 'MelKit Firebase client configuration is present.'

