#!/usr/bin/env bash
# Fails when a release APK exceeds the size budget. Usage: check-apk-size.sh <apk> [max-mib]
set -euo pipefail

apk="${1:?usage: check-apk-size.sh <apk> [max-mib]}"
max_mib="${2:-65}"
max_bytes=$((max_mib * 1024 * 1024))

test -f "$apk" || { echo "APK not found: $apk" >&2; exit 1; }
bytes="$(stat -c%s "$apk")"
printf '%s %s bytes (%d MiB budget)\n' "$(basename "$apk")" "$bytes" "$max_mib"
if [ "$bytes" -gt "$max_bytes" ]; then
  echo "$(basename "$apk") exceeds the $max_mib MiB budget." >&2
  exit 1
fi
