#!/usr/bin/env bash
#
# Build a self-contained jpackage app-image that bundles a trimmed JRE, so the
# tool runs on a machine with no Java installed. Cross-platform: works on Linux,
# macOS and Windows (Git Bash). jpackage emits the native layout per OS:
#   Linux/Windows -> dist/nexus-export/   (bin/nexus-export | nexus-export.exe)
#   macOS         -> dist/nexus-export.app (Contents/MacOS/nexus-export)
#
# Prerequisite: target/nexus-export.jar (run `./mvnw -T1C clean package` first).
# Usage: package/build-appimage.sh [app-version]    e.g. 1.2.3
#
# app-version is bundle metadata only (the tool reports its own --version). It is
# passed to jpackage ONLY when valid on every OS: macOS requires one to three
# dot-separated integers whose first number is >= 1. For anything else (e.g. a
# 0.x tag) jpackage's default is used so the build still succeeds.
#
set -euo pipefail

VERSION="${1:-}"
JAR="target/nexus-export.jar"
INPUT_DIR="target/jpackage-input"
DEST="dist"
MAIN_CLASS="be.selckin.nexus.export.Main"

[ -f "$JAR" ] || { echo "error: missing $JAR — run './mvnw -T1C clean package' first" >&2; exit 1; }

# Optional --app-version flag held in positional params so "$@" stays safe under
# `set -u` even when empty (portable down to the bash 3.2 on macOS runners).
if [[ "$VERSION" =~ ^[1-9][0-9]*(\.[0-9]+){0,2}$ ]]; then
  set -- --app-version "$VERSION"
else
  [ -n "$VERSION" ] && echo "note: '$VERSION' is not a valid jpackage app-version (macOS needs 1-3 dotted integers, first >= 1); using jpackage default" >&2
  set --
fi

# Modules the app references (static analysis) ...
BASE_MODULES="$(jdeps --multi-release 21 --ignore-missing-deps --print-module-deps "$JAR")"
# ... plus modules resolved reflectively / via ServiceLoader that jdeps misses:
#   jdk.crypto.ec - EC (ECDHE) cipher suites + EC certificates for the HTTPS calls
#   jdk.charsets  - full charset coverage when decoding HTTP responses
MODULES="${BASE_MODULES},jdk.crypto.ec,jdk.charsets"
echo "bundled modules: $MODULES"

rm -rf "$INPUT_DIR" "$DEST"
mkdir -p "$INPUT_DIR"
cp "$JAR" "$INPUT_DIR/"

jpackage \
  --type app-image \
  --name nexus-export \
  "$@" \
  --input "$INPUT_DIR" \
  --main-jar nexus-export.jar \
  --main-class "$MAIN_CLASS" \
  --add-modules "$MODULES" \
  --jlink-options "--strip-native-commands --strip-debug --no-man-pages --no-header-files --compress=zip-6" \
  --dest "$DEST"

echo "app-image written under $DEST/:"
ls "$DEST"
