#!/bin/sh
# Copies the SGWCMAID cabinet catalog into the app bundle.
#
# The catalog is deployment data and must never be committed. CI injects it
# through MAIMAID_KEYCHIPS_FILE after decoding the MAIMAID_KEYCHIPS_GZIP
# secret; local testers can either set
# MAIMAID_KEYCHIPS_FILE in the environment or set SGWCMAID_KEYCHIPS_FILE in
# ios/Config/Secrets.xcconfig. Without either input an empty catalog is bundled
# so builds and unit tests still work, and imports fall back to the legacy
# single keychip.

set -eu

destination="${TARGET_BUILD_DIR:?}/${UNLOCALIZED_RESOURCES_FOLDER_PATH:?}/keychips.json"
source="${MAIMAID_KEYCHIPS_FILE:-${SGWCMAID_KEYCHIPS_FILE:-}}"

mkdir -p "$(dirname "$destination")"

if [ -n "$source" ]; then
  if [ ! -f "$source" ]; then
    echo "error: SGWCMAID keychip catalog not found at $source" >&2
    exit 1
  fi
  cp "$source" "$destination"
elif [ -n "${MAIMAID_KEYCHIPS:-}" ]; then
  printf '%s\n' "$MAIMAID_KEYCHIPS" > "$destination"
else
  printf '%s\n' '{"version":0,"source":"","provinces":[],"keychips":[]}' > "$destination"
fi
