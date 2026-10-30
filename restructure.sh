#!/usr/bin/env bash
# Turns the flat repo into the standard Gradle layout (CI only; repo stays flat).
set -e

DEST=app/src/main
mkdir -p "$DEST/kotlin/com/pinshare/app"

cp -f ./*.kt "$DEST/kotlin/com/pinshare/app/"
cp -f AndroidManifest.xml "$DEST/AndroidManifest.xml"
cp -f app.build.gradle.kts app/build.gradle.kts

echo "Layout ready:"
find app -type f | sort
