#!/usr/bin/env bash
# Mirror docs/play-store/<locale>/{short,full,release-notes}.txt into
# the fastlane/metadata/android/<locale-fastlane>/ tree consumed by
# both the Play Console publish action and F-Droid's catalogue build.
#
# Single source of truth lives in docs/play-store/ (per-locale folders
# matching Play's language codes); this script does the one-way sync
# into fastlane's expected layout. Re-run after every translation pass
# and after every versionCode bump.
#
# Usage:  ./docs/play-store/sync-to-fastlane.sh <versionCode>
# Defaults to versionCode=1 when none given.

set -euo pipefail

VERSION_CODE="${1:-1}"
REPO_ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
cd "$REPO_ROOT"

# Locale mapping: docs/play-store/<key>  →  fastlane/metadata/android/<value>
declare -A LOCALE_MAP=(
  [en]=en-US
  [cs]=cs-CZ
  [de]=de-DE
  [es]=es-ES
  [fr]=fr-FR
  [it]=it-IT
  [ja]=ja-JP
  [nl]=nl-NL
  [pl]=pl-PL
  [pt-BR]=pt-BR
  [ru]=ru-RU
  [tr]=tr-TR
)

for src in "${!LOCALE_MAP[@]}"; do
  dst="${LOCALE_MAP[$src]}"
  src_dir="docs/play-store/$src"
  dst_dir="fastlane/metadata/android/$dst"
  if [[ ! -d "$src_dir" ]]; then
    echo "skip: $src_dir not found" >&2
    continue
  fi
  mkdir -p "$dst_dir/changelogs"
  cp "$src_dir/short.txt"          "$dst_dir/short_description.txt"
  cp "$src_dir/full.txt"           "$dst_dir/full_description.txt"
  cp "$src_dir/release-notes.txt"  "$dst_dir/changelogs/${VERSION_CODE}.txt"
  echo "sync: $src → $dst (changelog vc=$VERSION_CODE)"
done

echo "Done. Don't forget to commit the fastlane/ tree."
