#!/usr/bin/env bash
# Example template — copy to `build-production.sh` (gitignored) and fill in.
#
# Single-shot publish pipeline. Edits all the secrets above the dotted line,
# runs the standard fastlane lanes below it.
#
# Usage:
#   ./build-production.sh                # build + push to Play Console internal
#   ./build-production.sh --promote      # same + promote internal → production
#   ./build-production.sh --screenshots  # also regenerate Play Store screenshots
#
# Prereqs (one-time):
#   - keystore.properties present at repo root (see keystore.properties.example)
#   - play-service-account.json present at repo root, generated from
#     https://console.cloud.google.com/iam-admin/serviceaccounts and granted
#     the "Release Manager" role on Play Console
#   - `bundle install` already ran (Gemfile installs fastlane + plugins)
#   - Android SDK reachable via local.properties

set -euo pipefail

# ─── SECRETS — keep this file gitignored ─────────────────────────────
# These mirror keys in keystore.properties so the script also works in
# environments where you'd rather pass via env (CI runners, etc.).
export FASTLANE_GPSINFO_KEYSTORE_FILE="/absolute/path/to/gpsinfo-release.jks"
export FASTLANE_GPSINFO_KEYSTORE_PASSWORD="REPLACE_ME"
export FASTLANE_GPSINFO_KEY_ALIAS="gpsinfo"
export FASTLANE_GPSINFO_KEY_PASSWORD="REPLACE_ME"
export SUPPLY_JSON_KEY_FILE="$(pwd)/play-service-account.json"

# Optional: skip fastlane's confirmation prompts for a fully unattended run.
# Comment out to require human approval before each Play Console push.
export FASTLANE_SKIP_UPDATE_CHECK="1"
export FASTLANE_HIDE_CHANGELOG="1"
# export FASTLANE_DISABLE_COLORS="1"
# ──────────────────────────────────────────────────────────────────────

PROMOTE=0
SCREENSHOTS=0
for arg in "$@"; do
  case "$arg" in
    --promote)     PROMOTE=1 ;;
    --screenshots) SCREENSHOTS=1 ;;
    *) echo "unknown arg: $arg"; exit 2 ;;
  esac
done

echo "▸ 1/4  Unit tests"
bundle exec fastlane verify

echo "▸ 2/4  Building signed AAB"
bundle exec fastlane bundle

if [[ "$SCREENSHOTS" == "1" ]]; then
  echo "▸ 2.5  Screenshots (needs a running emulator)"
  bundle exec fastlane screenshots
fi

echo "▸ 3/4  Upload to Play Console internal track"
bundle exec fastlane deploy_internal

if [[ "$PROMOTE" == "1" ]]; then
  echo "▸ 4/4  Promoting internal → production (10% rollout)"
  bundle exec fastlane deploy_production
else
  echo "▸ 4/4  Skipped production promotion (pass --promote to enable)"
fi

echo "✓ done."
