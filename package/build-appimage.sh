#!/usr/bin/env bash
#
# Build a self-contained jpackage app-image that bundles a trimmed JRE, so the
# tool runs on a machine with no Java installed. Cross-platform: works on Linux,
# macOS and Windows (Git Bash) — jpackage produces the native layout per OS.
#
# Prerequisite: target/nexus-export.jar (run `./mvnw -T1C clean package` first).
# Usage: package/build-appimage.sh <app-version>     e.g. 1.2.3
#
set -euo pipefail

VERSION="${1:?usage: build-appimage.sh <app-version> (e.g. 1.2.3)}"
JAR="target/nexus-export.jar"
INPUT_DIR="target/jpackage-input"
DEST="dist"
MAIN_CLASS="be.selckin.nexus.export.Main"

[ -f "$JAR" ] || { echo "error: missing $JAR — run './mvnw -T1C clean package' first" >&2; exit 1; }

# Modules the app actually references (static analysis of the uber-jar) ...
BASE_MODULES="$(jdeps --multi-release 21 --ignore-missing-deps --print-module-deps "$JAR")"
# ... plus modules resolved reflectively / via ServiceLoader that jdeps cannot see:
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
  --app-version "$VERSION" \
  --input "$INPUT_DIR" \
  --main-jar nexus-export.jar \
  --main-class "$MAIN_CLASS" \
  --add-modules "$MODULES" \
  --jlink-options "--strip-native-commands --strip-debug --no-man-pages --no-header-files --compress=zip-6" \
  --dest "$DEST"

echo "app-image written to $DEST/nexus-export"
