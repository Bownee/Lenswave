#!/usr/bin/env bash
# Verifies that R8 kept every symbol the minified instrumentation build and Proton's native
# bridge rely on, and names the missing symbol when it did not.
# Usage: verify-minified-contract.sh <mapping-directory>
set -euo pipefail

mapping_dir="${1:?usage: verify-minified-contract.sh <mapping-directory>}"
seeds="$mapping_dir/seeds.txt"
mapping="$mapping_dir/mapping.txt"
status=0

for file in "$seeds" "$mapping"; do
  test -s "$file" || { echo "Missing or empty R8 output: $file" >&2; exit 1; }
done

require_seed() {
  local description="$1" pattern="$2"
  if ! grep -Eq -- "$pattern" "$seeds"; then
    echo "R8 did not keep $description (pattern: $pattern)" >&2
    status=1
  fi
}

require_seed "the LenswaveApplication companion used by the test runner" \
  'com\.bownee\.lenswave\.LenswaveApplication: com\.bownee\.lenswave\.LenswaveApplication\$Companion Companion'
require_seed "LenswaveApplication.Companion.disableAccountSessionStartupForTests" \
  'com\.bownee\.lenswave\.LenswaveApplication\$Companion: void disableAccountSessionStartupForTests\$app\(\)'

for callback in onResponse onCallback onRead onWrite onSeek onYield onProgress \
  onSendHttpRequest onHttpResponseRead onAccountRequest onRecordMetric \
  onFeatureEnabled onSha1 onDispose; do
  require_seed "Proton native bridge callback $callback" \
    "me\.proton\.drive\.sdk\.internal\.ProtonDriveSdkNativeClient: .* ${callback}\\("
done

require_seed "kotlin.coroutines.Continuation for Retrofit suspend calls" 'kotlin\.coroutines\.Continuation'

if ! grep -Eq '^retrofit2\.Response -> ' "$mapping"; then
  echo "retrofit2.Response is missing from the R8 mapping; Retrofit suspend calls will fail at runtime." >&2
  status=1
fi

if [ "$status" -eq 0 ]; then
  echo "Minified instrumentation contract holds."
fi
exit "$status"
