#!/usr/bin/env bash
# One targeted Gradle build at a time, arm64 only, limited workers.
# Usage: encrogram/tools/build.sh <gradle task> [more tasks or flags]
#   e.g. encrogram/tools/build.sh :encrogram-core:compileDebugKotlin
#        encrogram/tools/build.sh :Encrogram_App:assembleDebug
# Waits while any other Gradle or Xcode build runs on this Mac (ours or another project's),
# holds a lock so two agents never build at once, and logs the wall-clock time.
set -euo pipefail

cd "$(dirname "$0")/../.."   # telegram-android/
LOCK=/tmp/encrogram-build.lock
LOG=encrogram/build-times.log
[ $# -ge 1 ] || { echo "usage: $0 <gradle task> [...]" >&2; exit 2; }

other_build_running() {
  # A build is running if a Gradle client or xcodebuild process exists (idle daemons don't count).
  pgrep -f 'org.gradle.wrapper.GradleWrapperMain|org.gradle.launcher.GradleMain|xcodebuild' >/dev/null 2>&1
}

waited=0
until mkdir "$LOCK" 2>/dev/null; do
  [ $waited -eq 0 ] && echo "Another Encrogram build holds $LOCK; waiting..."
  sleep 10; waited=$((waited + 10))
  [ $waited -ge 3600 ] && { echo "Gave up after 1 h waiting for $LOCK" >&2; exit 3; }
done
trap 'rmdir "$LOCK" 2>/dev/null || true' EXIT

waited=0
while other_build_running; do
  [ $waited -eq 0 ] && echo "Another build is running on this Mac; waiting for it to finish..."
  sleep 15; waited=$((waited + 15))
  [ $waited -ge 3600 ] && { echo "Gave up after 1 h waiting for another build" >&2; exit 3; }
done

start=$(date +%s)
status=0
./gradlew "$@" --max-workers=6 -Pandroid.injected.build.abi=arm64-v8a --console=plain || status=$?
end=$(date +%s)
printf '%s\t%ss\texit=%s\t%s\n' "$(date '+%Y-%m-%d %H:%M')" "$((end - start))" "$status" "$*" >> "$LOG"
exit $status
