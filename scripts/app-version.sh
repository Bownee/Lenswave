#!/usr/bin/env bash
# Prints VERSION_NAME and VERSION_CODE from version.properties as GitHub Actions outputs.
# Mirrors the validation in build.gradle.kts so CI and Gradle can never disagree on a version.
set -euo pipefail

cd "$(dirname "$0")/.."

read_property() {
  local value
  value="$(grep "^$1=" version.properties | cut -d= -f2- | tr -d '[:space:]')"
  test -n "$value" || { echo "$1 must be set in version.properties" >&2; exit 1; }
  printf '%s' "$value"
}

version_name="$(read_property VERSION_NAME)"
version_code="$(read_property VERSION_CODE)"

[[ "$version_name" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]] \
  || { echo "VERSION_NAME '$version_name' is not a semantic version" >&2; exit 1; }
[[ "$version_code" =~ ^[1-9][0-9]*$ ]] \
  || { echo "VERSION_CODE '$version_code' must be a positive integer" >&2; exit 1; }

echo "version_name=$version_name"
echo "version_code=$version_code"
