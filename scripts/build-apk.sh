#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${ANDROID_SDK_ROOT:-}" ]]; then
  export ANDROID_SDK_ROOT=/opt/homebrew/share/android-commandlinetools
fi
export ANDROID_HOME="$ANDROID_SDK_ROOT"

./gradlew assembleRelease
