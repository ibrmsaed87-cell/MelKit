#!/usr/bin/env bash
# Configuration only: no SDK requests, signing access or Android build commands.
melkit_resolve_ad_mode() {
  export BUILD_AAB="${BUILD_AAB:-false}"
  case "$BUILD_AAB" in
    true|false) ;;
    *) printf '%s\n' 'BUILD_AAB must be true or false.' >&2; return 1 ;;
  esac
  export MELKIT_LIVE_ADS="${MELKIT_LIVE_ADS:-$BUILD_AAB}"
  case "$MELKIT_LIVE_ADS" in
    true|false) ;;
    *) printf '%s\n' 'MELKIT_LIVE_ADS must be true or false.' >&2; return 1 ;;
  esac
  if [[ "$BUILD_AAB" == true && "$MELKIT_LIVE_ADS" != true ]]; then
    printf '%s\n' 'Publishing an AAB requires MELKIT_LIVE_ADS=true. Use an APK with false for repeated phone tests.' >&2
    return 1
  fi
}
melkit_resolve_ad_mode
