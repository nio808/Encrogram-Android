#!/usr/bin/env bash
# Fetches the SimpleX native libraries used by :encrogram-core (MVP shortcut: prebuilt, not our own build).
#
# Source: the official SimpleX Chat v7.0.4 release APK for arm64 (chat core simplex-chat-7.0.0.12),
# pinned by SHA-256. Only three libraries are taken from it:
#   libsimplex.so  Haskell chat core (GHC RTS, SQLCipher and OpenSSL linked statically)
#   libsupport.so  Haskell android-support (line buffering)
#   libapp-lib.so  JNI glue exporting Java_chat_simplex_common_platform_CoreKt_*
# They land in encrogram/core/src/main/jniLibs/arm64-v8a/ (git-ignored). The APK is deleted afterwards.
#
# Usage: encrogram/tools/fetch-simplex-libs.sh        (needs: gh, unzip, shasum)
set -euo pipefail

TAG=v7.0.4
REPO=simplex-chat/simplex-chat
ASSET=simplex-aarch64.apk
APK_SHA256=17966066a1e4369ebc8c569baec9607213b510a53b328279eb516facb3e7db29
LIBS=(libsimplex.so libsupport.so libapp-lib.so)
# Checksums of the libraries as extracted from the APK above (second line of defence).
LIB_SHA256_libsimplex=1856cdc7e1dd3cee2dac3d7d013d1e06c3facc12879f5775ea93ba92161dbfb4
LIB_SHA256_libsupport=acbabf60244517ec227f84afdbb166c7f2d00e31274d229bbba70926ff240f36
LIB_SHA256_libapp_lib=346394c5c6edb1d0bb79444e93847b7be4f5c06e3a5bf4ba0334c2d8d724bd4d

cd "$(dirname "$0")/.."   # encrogram/
DEST="core/src/main/jniLibs/arm64-v8a"

for tool in gh unzip shasum; do
  command -v "$tool" >/dev/null 2>&1 || { echo "missing tool: $tool" >&2; exit 2; }
done

TMP="$(mktemp -d "${TMPDIR:-/tmp}/encrogram-simplex-libs.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

echo "Downloading $ASSET from $REPO $TAG ..."
gh release download "$TAG" -R "$REPO" -p "$ASSET" -D "$TMP"

actual="$(shasum -a 256 "$TMP/$ASSET" | awk '{print $1}')"
if [ "$actual" != "$APK_SHA256" ]; then
  echo "SHA-256 mismatch for $ASSET: expected $APK_SHA256, got $actual" >&2
  exit 1
fi
echo "APK checksum OK"

mkdir -p "$TMP/out"
unzip -q -j -o "$TMP/$ASSET" $(printf 'lib/arm64-v8a/%s ' "${LIBS[@]}") -d "$TMP/out"

for lib in "${LIBS[@]}"; do
  var="LIB_SHA256_$(echo "${lib%.so}" | tr '-' '_')"
  expected="${!var}"
  got="$(shasum -a 256 "$TMP/out/$lib" | awk '{print $1}')"
  if [ "$got" != "$expected" ]; then
    echo "SHA-256 mismatch for $lib: expected $expected, got $got" >&2
    exit 1
  fi
done

mkdir -p "$DEST"
for lib in "${LIBS[@]}"; do
  mv -f "$TMP/out/$lib" "$DEST/$lib"
done
echo "Installed ${LIBS[*]} into encrogram/$DEST"
# The APK and temp dir are removed by the EXIT trap.
